package com.powerjob.archive.core.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

@Service
public class RemotePathService {
    private static final Pattern PACKAGE_NAME = Pattern.compile("batch_\\d{5}\\.tar\\.gz");
    private final JdbcTemplate jdbc;

    public RemotePathService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String objectKey(long outputPackageId) {
        var row = jdbc.queryForMap("SELECT r.job_date,l.legacy_batch,l.provider_code,p.new_package_name " +
                "FROM output_package p JOIN source_package s ON s.id=p.source_package_id " +
                "JOIN delivery_run r ON r.id=s.run_id JOIN production_line l ON l.id=p.production_line_id WHERE p.id=?",
                outputPackageId);
        String packageName = row.get("new_package_name").toString();
        validatePackageName(packageName);
        LocalDate jobDate = LocalDate.parse(row.get("job_date").toString());
        String provider = row.get("provider_code").toString();
        int legacyBatch = ((Number) row.get("legacy_batch")).intValue();
        return "june/" + jobDate + "-" + legacyBatch + "-" + provider + "/" + packageName;
    }

    public String ftpDirectory(long outputPackageId) {
        var row = jdbc.queryForMap("SELECT r.job_date,l.provider_code FROM output_package p " +
                "JOIN source_package s ON s.id=p.source_package_id JOIN delivery_run r ON r.id=s.run_id " +
                "JOIN production_line l ON l.id=p.production_line_id WHERE p.id=?", outputPackageId);
        return row.get("provider_code") + "/" + LocalDate.parse(row.get("job_date").toString())
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static void validatePackageName(String packageName) {
        if (packageName == null || !PACKAGE_NAME.matcher(packageName).matches()
                || Path.of(packageName).isAbsolute() || packageName.contains("..")) {
            throw new IllegalArgumentException("Invalid delivery package name");
        }
    }
}
