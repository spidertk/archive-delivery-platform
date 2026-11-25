package com.powerjob.archive.server.service.ingestmonitor;

import java.time.Instant;

/** 来源观察契约；对象逐页传给 PG，Java 不保留整桶对象清单。 */
public final class SourceSnapshot {
    private SourceSnapshot() {}
    public record Item(String key, long size, String etag, Instant modifiedAt) {}
    public record Source(long id, String uri) {
        public String bucket() { return java.net.URI.create(uri).getHost(); }
        public String prefix() { return java.net.URI.create(uri).getPath().substring(1); }
        public String name() { return com.powerjob.archive.core.service.IngestSourceService.channelCode(uri); }
        public String channelPrefix() { return prefix().isEmpty() ? "" : prefix().split("/",2)[0]+"/"; }
        public String key() { return "oss://"+bucket()+"/"+channelPrefix(); }
    }
    public record Counts(long objects, long bytes, long addedObjects, long addedBytes,
                         long changedObjects, long changedBytes, long removedObjects, long removedBytes) {
        boolean changed() { return addedObjects + changedObjects + removedObjects > 0; }
    }
}
