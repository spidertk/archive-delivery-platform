package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 接纳已完成 FTP 实物重命名的逐文件回验事实，不改历史节点结果、包血缘或交付状态。 */
@Service
public class FtpNamingService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DeliveryRepository deliveries;
    private final DeliveryEventRepository events;
    private final ObjectMapper mapper;

    public FtpNamingService(JdbcTemplate jdbc,TransactionTemplate transactions,DeliveryRepository deliveries,
            DeliveryEventRepository events,ObjectMapper mapper) {
        this.jdbc=jdbc;this.transactions=transactions;this.deliveries=deliveries;this.events=events;this.mapper=mapper;
    }

    public Map<String,Object> adopt(String vendor,String entityCode,Command command) {
        // 管理入口只接纳外部逐文件 GET/SHA 已通过的收据；Secret、Shell 和任意路径不进入接口。
        if (command==null || command.deliveryDate()==null || command.reason()==null || command.reason().isBlank()
                || command.proofSha256()==null || !command.proofSha256().matches("[a-f0-9]{64}")
                || command.files()==null || command.files().isEmpty()) throw new IllegalArgumentException("Complete verified naming receipt is required");
        var entity=deliveries.entityByBusinessCode(vendor,entityCode);
        return transactions.execute(status->{
            var parents=jdbc.queryForList("SELECT DISTINCT coalesce(s.batch_delivery_id,s.delivery_id) FROM delivery_task t " +
                    "JOIN delivery_submit s ON s.delivery_id=t.delivery_id WHERE t.delivery_entity_id=? AND t.delivery_date=? ORDER BY 1",
                    Long.class,entity.deliveryEntityId(),command.deliveryDate());
            for (long parent:parents) jdbc.queryForObject("SELECT delivery_id FROM delivery_submit WHERE delivery_id=? FOR UPDATE",Long.class,parent);
            // 取得命名行锁后重新读取，不能与另一个批次的新增命名穿插，也不触碰正在下载的任务。
            jdbc.queryForObject("SELECT next_value FROM delivery_ftp_day_sequence WHERE delivery_entity_id=? AND delivery_date=? FOR UPDATE",
                    Long.class,entity.deliveryEntityId(),command.deliveryDate());
            var tasks=jdbc.queryForList("SELECT task_id,delivery_id,execution_status,ftp_file_name FROM delivery_task " +
                    "WHERE delivery_entity_id=? AND delivery_date=? ORDER BY task_id FOR UPDATE",entity.deliveryEntityId(),command.deliveryDate());
            if (tasks.size()!=command.files().size() || tasks.stream().anyMatch(t->!"SUCCEEDED".equals(t.get("execution_status")))) {
                throw new IdempotencyConflictException("Naming correction requires complete quiescent entity/day coverage");
            }
            var seen=new HashSet<Long>();
            for (int i=0;i<command.files().size();i++) {
                FileProof file=command.files().get(i);
                var task=tasks.get(i);
                String expected=String.format("batch_%07d.tar.gz",i+1);
                if (file.taskId()!=((Number)task.get("task_id")).longValue() || !seen.add(file.taskId())
                        || !file.oldName().equals(task.get("ftp_file_name")) || !expected.equals(file.newName())) {
                    throw new IdempotencyConflictException("Naming receipt order or previous names changed");
                }
                Integer matches=jdbc.queryForObject("SELECT count(*) FROM delivery_node WHERE task_id=? AND node_type='ftp.download-verify' " +
                        "AND status='SUCCEEDED' AND (output_locator->>'sizeBytes')::bigint=? AND output_locator->>'sha256'=?",
                        Integer.class,file.taskId(),file.sizeBytes(),file.sha256());
                if (matches<1) throw new IllegalArgumentException("Naming receipt does not match a genuine successful FTP result");
            }
            // 更新渠道别名与下一序号；事务回滚时不保留半套命名事实，真实文件重命名收据仍可重试接纳。
            jdbc.update("UPDATE delivery_task SET ftp_sequence=NULL WHERE delivery_entity_id=? AND delivery_date=?",entity.deliveryEntityId(),command.deliveryDate());
            for (int i=0;i<command.files().size();i++) {
                var file=command.files().get(i);
                jdbc.update("UPDATE delivery_task SET ftp_file_name=?,ftp_sequence=?,updated_at=now() WHERE task_id=?",file.newName(),i+1,file.taskId());
            }
            jdbc.update("UPDATE delivery_ftp_day_sequence SET next_value=?,updated_at=now() WHERE delivery_entity_id=? AND delivery_date=?",
                    tasks.size()+1,entity.deliveryEntityId(),command.deliveryDate());
            var payload=mapper.createObjectNode().put("schemaVersion",1).put("proofSha256",command.proofSha256())
                    .put("deliveryDate",command.deliveryDate().toString()).put("fileCount",tasks.size()).put("reason",command.reason());
            for (long parent:parents) events.append(parent,null,null,entity.deliveryEntityId(),null,"FTP_NAMES_RECONCILED","INFO",null,
                    "Verified FTP physical names adopted; historical node results unchanged",payload,"api","ftp-names-"+UUID.randomUUID());
            return Map.of("entityCode",entityCode,"fileCount",tasks.size(),"nextSequence",tasks.size()+1);
        });
    }

    public record Command(LocalDate deliveryDate,String reason,String proofSha256,List<FileProof> files) {}
    public record FileProof(long taskId,String oldName,String newName,long sizeBytes,String sha256) {}
}
