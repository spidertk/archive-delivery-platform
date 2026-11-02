package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Base64;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FtpNodeConfig(Map<String, Line> lines) {
    public static final String REMOTE_ROOT = "/srv/maas-sftp/maasData/archive";

    public Line line(long lineId) {
        Line line = lines == null ? null : lines.get(Long.toString(lineId));
        if (line == null) {
            throw new IllegalArgumentException("FTP line " + lineId + " is not configured");
        }
        line.validate();
        return line;
    }

    public void validate() {
        for (long lineId = 1; lineId <= 3; lineId++) {
            line(lineId);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String host, String user, String key, String jumpHost, String jumpUser, String jumpKey,
                       String hostKey, String jumpHostKey, String remoteRoot,
                       String downloadScript, String publishScript) {
        void validate() {
            if (blank(host) || blank(user) || blank(key)) {
                throw new IllegalArgumentException("FTP host/user/key are required");
            }
            if (!REMOTE_ROOT.equals(remoteRoot)) {
                throw new IllegalArgumentException("FTP remoteRoot must be " + REMOTE_ROOT);
            }
            if (blank(downloadScript) || blank(publishScript)) {
                throw new IllegalArgumentException("FTP scripts are required");
            }
            validateHostKey(hostKey);
            if (!blank(jumpHost)) {
                if (blank(jumpUser) || blank(jumpKey)) {
                    throw new IllegalArgumentException("FTP jumpUser/jumpKey are required with jumpHost");
                }
                validateHostKey(jumpHostKey);
            }
        }
    }

    static String validateHostKey(String value) {
        try {
            String[] fields = value.split("\\s+");
            if (fields.length != 3 || fields[0].isBlank()
                    || !(fields[1].startsWith("ssh-") || fields[1].startsWith("ecdsa-") || fields[1].startsWith("sk-"))) {
                throw new IllegalArgumentException("Invalid SSH host public key");
            }
            Base64.getDecoder().decode(fields[2]);
            return value;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid SSH host public key");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
