package com.powerjob.archive.core.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.List;
import java.util.Map;

@Service
public class IngestSourceService {
    private final JdbcTemplate jdbc;
    public IngestSourceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 来源登记只接受无签名、无用户信息的 OSS URI，凭据仍由 Pipeline sourceDownload 维护。 */
    public Map<String, Object> create(String ossUri, String ossType) {
        // 标识只从 URI 推导，登记接口既不接受手工渠道名也不接收凭据。
        validateUri(ossUri);
        if (ossType == null || ossType.isBlank()) {
            throw new IllegalArgumentException("ossType is required");
        }
        long id = jdbc.queryForObject("INSERT INTO ingest_source(oss_uri,channel_code,oss_type) VALUES(?,?,?) RETURNING source_id",
                Long.class, ossUri, channelCode(ossUri), ossType);
        return Map.of("sourceId", id, "ossUri", ossUri, "channelCode", channelCode(ossUri), "availabilityStatus", "AVAILABLE");
    }

    /** 提交直接使用原始 URI；首次出现时登记内部来源，调用方不需要管理 sourceId。 */
    public long resolve(String ossUri) {
        validateUri(ossUri);
        return jdbc.queryForObject("INSERT INTO ingest_source(oss_uri,channel_code,oss_type) " +
                        "VALUES(?,?,'ALIBABA_CLOUD') ON CONFLICT(oss_uri) DO UPDATE SET channel_code=EXCLUDED.channel_code " +
                        "RETURNING source_id", Long.class, ossUri, channelCode(ossUri));
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT source_id AS \"sourceId\",oss_uri AS \"ossUri\",availability_status AS \"availabilityStatus\"," +
                "channel_code AS \"channelCode\",oss_type AS \"ossType\",previous_refresh_bytes AS \"previousRefreshBytes\"," +
                "latest_refresh_bytes AS \"latestRefreshBytes\" FROM ingest_source ORDER BY source_id");
    }

    /** 展示标识自动取桶下第一级目录；桶根来源以桶名显示，调用方不能手工覆盖。 */
    public static String channelCode(String ossUri) {
        URI uri=URI.create(ossUri);
        String path=uri.getPath().replaceFirst("^/","");
        return path.isEmpty() ? uri.getHost() : path.split("/",2)[0];
    }

    private static void validateUri(String ossUri) {
        if (ossUri == null || ossUri.isBlank()) throw new IllegalArgumentException("ossUri is required");
        URI uri = URI.create(ossUri);
        if (!"oss".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || uri.getPath() == null
                || List.of(uri.getPath().split("/", -1)).contains("..")) {
            throw new IllegalArgumentException("ossUri must be an unsigned oss://bucket/prefix URI");
        }
    }
}
