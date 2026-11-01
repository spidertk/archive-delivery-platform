package com.powerjob.archive.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "archive.ftp")
public class FtpDeliveryProperties {
    public static final String REMOTE_ROOT = "/srv/maas-sftp/maasData/archive";
    private Map<Long, Line> lines = new LinkedHashMap<>();

    public Map<Long, Line> getLines() { return lines; }
    public void setLines(Map<Long, Line> lines) { this.lines = lines; }

    public Line line(long lineId) {
        Line line = lines.get(lineId);
        if (line == null) throw new IllegalStateException("FTP line " + lineId + " is not configured");
        return line;
    }

    public void validate() {
        for (long lineId = 1; lineId <= 3; lineId++) {
            Line line = line(lineId);
            line.validate(lineId);
        }
    }

    public static class Line {
        private String host;
        private String user;
        private String key;
        private String jumpHost;
        private String jumpUser;
        private String jumpKey;
        private String remoteRoot = REMOTE_ROOT;
        private String downloadScript = "/opt/archive-delivery/bin/sg_download.sh";
        private String publishScript = "/opt/archive-delivery/bin/publish_package.sh";

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getJumpHost() { return jumpHost; }
        public void setJumpHost(String jumpHost) { this.jumpHost = jumpHost; }
        public String getJumpUser() { return jumpUser; }
        public void setJumpUser(String jumpUser) { this.jumpUser = jumpUser; }
        public String getJumpKey() { return jumpKey; }
        public void setJumpKey(String jumpKey) { this.jumpKey = jumpKey; }
        public String getRemoteRoot() { return remoteRoot; }
        public void setRemoteRoot(String remoteRoot) { this.remoteRoot = remoteRoot; }
        public String getDownloadScript() { return downloadScript; }
        public void setDownloadScript(String downloadScript) { this.downloadScript = downloadScript; }
        public String getPublishScript() { return publishScript; }
        public void setPublishScript(String publishScript) { this.publishScript = publishScript; }

        void validate(long lineId) {
            if (host == null || host.isBlank() || user == null || user.isBlank() || key == null || key.isBlank()) {
                throw new IllegalStateException("FTP line " + lineId + " host/user/key are required");
            }
            if (!REMOTE_ROOT.equals(remoteRoot)) {
                throw new IllegalStateException("FTP line " + lineId + " remoteRoot must be " + REMOTE_ROOT);
            }
        }
    }
}
