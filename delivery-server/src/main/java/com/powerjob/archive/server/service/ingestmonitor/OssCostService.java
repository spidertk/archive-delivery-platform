package com.powerjob.archive.server.service.ingestmonitor;

import org.springframework.stereotype.Service;
import java.math.*;
import java.util.*;

/** 官方新加坡标准 LRS 参考价估算存储，独立于进数/交付量，不计算用户未要求的下载费用。 */
@Service
public class OssCostService {
    static final BigDecimal STORAGE_PRICE=new BigDecimal("0.0170");
    static final BigDecimal USD_CNY_RATE=new BigDecimal("6.6777");
    static final String EXCHANGE_DATE="2026-11-20";
    static final String EXCHANGE_URL="https://api.frankfurter.dev/v1/2026-11-20?base=USD&symbols=CNY";
    static final long BILLING_GB=1_073_741_824L;
    static final String PRICE_URL="https://www.alibabacloud.com/en/product/oss/pricing?_p_lc=1";

    public Map<String,Object> pricing() {
        // 美元官方价与有日期的汇率同时公开，人民币是整月参考估算，不冒充实时汇率或已产生账单。
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("currency","CNY");result.put("storagePerGbMonth",STORAGE_PRICE.multiply(USD_CNY_RATE));
        result.put("sourceCurrency","USD");result.put("sourceStoragePerGbMonth",STORAGE_PRICE);
        result.put("usdCnyRate",USD_CNY_RATE);result.put("exchangeRateDate",EXCHANGE_DATE);result.put("exchangeRateSource",EXCHANGE_URL);
        result.put("bytesPerGb",BILLING_GB);result.put("region","ap-southeast-1");result.put("storageClass","Standard LRS");
        result.put("sourceUrl",PRICE_URL);result.put("verifiedOn","2026-11-22");
        result.put("basis","预估月存储费：当前监控存量按新加坡标准存储官方美元单价及有日期的参考汇率折算人民币，持续存放一个月，不含免费额度、套餐折扣及其他费用。");
        return result;
    }

    public Map<String,Object> estimate(long storedBytes) {
        return Map.of("currency","CNY","monthlyStorage",charge(storedBytes));
    }

    public Map<String,Object> total(List<Map<String,Object>> channels) {
        // 只累计各逻辑渠道的已监控存量；不把目标上传量或四舍五入后的桶费用重复求和。
        long bytes=channels.stream().mapToLong(row->((Number)row.get("sizeBytes")).longValue()).sum();
        Map<String,Object> result=new LinkedHashMap<>(estimate(bytes));
        result.put("storedBytes",bytes);result.put("periodMonths",1);
        return result;
    }

    public List<Map<String,Object>> buckets(List<Map<String,Object>> channels) {
        // 同桶多渠道先合并监控存量，目标上传量仅用于说明去向，不把未扫描目标量冒充当前存储。
        Map<String,long[]> totals=new TreeMap<>();
        for (var channel : channels) totals.computeIfAbsent((String)channel.get("bucket"),key->new long[2])[0]+=((Number)channel.get("sizeBytes")).longValue();
        for (var channel : channels) {
            @SuppressWarnings("unchecked") var destinations=(List<Map<String,Object>>)channel.get("destinations");
            for (var target : destinations) {
                var monitored=totals.get((String)target.get("bucket"));
                if (monitored!=null) monitored[1]+=((Number)target.get("totalBytes")).longValue();
            }
        }
        List<Map<String,Object>> rows=new ArrayList<>();
        totals.forEach((bucket,values)->{
            Map<String,Object> row=new LinkedHashMap<>(estimate(values[0]));
            row.put("bucket",bucket);row.put("storedBytes",values[0]);row.put("deliveredBytes",values[1]);rows.add(row);
        });
        return rows;
    }

    static BigDecimal charge(long bytes) {
        return BigDecimal.valueOf(bytes).multiply(STORAGE_PRICE).multiply(USD_CNY_RATE)
                .divide(BigDecimal.valueOf(BILLING_GB),6,RoundingMode.HALF_UP);
    }
}
