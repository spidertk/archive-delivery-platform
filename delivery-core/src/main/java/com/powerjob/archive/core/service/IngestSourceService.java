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
    public Map<String, Object> create(String ossUri, String channelCode, String ossType) {
        validateUri(ossUri);
        if (channelCode == null || channelCode.isBlank() || ossType == null || ossType.isBlank()) {
            throw new IllegalArgumentException("channelCode and ossType are required");
        }
        long id = jdbc.queryForObject("INSERT INTO ingest_source(oss_uri,channel_code,oss_type) VALUES(?,?,?) RETURNING source_id",
                Long.class, ossUri, channelCode, ossType);
        return Map.of("sourceId", id, "ossUri", ossUri, "availabilityStatus", "AVAILABLE");
    }

    /** 提交直接使用原始 URI；首次出现时登记内部来源，调用方不需要管理 sourceId。 */
    public long resolve(String ossUri) {
        validateUri(ossUri);
        return jdbc.queryForObject("INSERT INTO ingest_source(oss_uri,channel_code,oss_type) " +
                        "VALUES(?,'ARCHIVE_SOURCE','ALIBABA_CLOUD') ON CONFLICT(oss_uri) DO UPDATE SET oss_uri=EXCLUDED.oss_uri " +
                        "RETURNING source_id", Long.class, ossUri);
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT source_id AS \"sourceId\",oss_uri AS \"ossUri\",availability_status AS \"availabilityStatus\"," +
                "channel_code AS \"channelCode\",oss_type AS \"ossType\",previous_refresh_bytes AS \"previousRefreshBytes\"," +
                "latest_refresh_bytes AS \"latestRefreshBytes\" FROM ingest_source ORDER BY source_id");
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
