package com.powerjob.archive.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "archive.oss")
public class OssDeliveryProperties {
    private Endpoint source = new Endpoint();
    private Endpoint hz = new Endpoint();
    private Endpoint sg = new Endpoint();

    public Endpoint getSource() { return source; }
    public void setSource(Endpoint source) { this.source = source; }
    public Endpoint getHz() { return hz; }
    public void setHz(Endpoint hz) { this.hz = hz; }
    public Endpoint getSg() { return sg; }
    public void setSg(Endpoint sg) { this.sg = sg; }

    public void validate() {
        source.validate("source");
        hz.validate("hz");
        sg.validate("sg");
    }

    public static class Endpoint {
        private String endpoint;
        private String bucket;
        private String accessKeyId;
        private String accessKeySecret;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
        public String getAccessKeySecret() { return accessKeySecret; }
        public void setAccessKeySecret(String accessKeySecret) { this.accessKeySecret = accessKeySecret; }

        void validate(String name) {
            if (isBlank(endpoint) || isBlank(bucket) || isBlank(accessKeyId) || isBlank(accessKeySecret)) {
                throw new IllegalStateException("OSS " + name + " endpoint/bucket/credentials are required");
            }
            if (bucket.contains("oss://")) {
                throw new IllegalStateException("OSS " + name + " bucket must not contain oss://");
            }
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
