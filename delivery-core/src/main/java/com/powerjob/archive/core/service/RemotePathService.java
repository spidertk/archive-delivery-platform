package com.powerjob.archive.core.service;

import com.powerjob.archive.core.domain.DeliveryTask;
import org.springframework.stereotype.Service;
import java.nio.file.Path;

@Service
public class RemotePathService {
    /** 外部路径包含 delivery/entity/date/file，跨日不会覆盖旧日期对象；配置 prefix 也做穿越校验。 */
    public String objectKey(String configuredPrefix, DeliveryTask task) {
        validatePackageName(task.newFileName());
        String prefix = configuredPrefix == null ? "" : configuredPrefix.replaceAll("^/+|/+$", "");
        if (prefix.contains("..") || prefix.contains("?") || prefix.contains("#")) throw new IllegalArgumentException("Unsafe OSS prefix");
        String relative = task.deliveryId() + "/entity-" + task.deliveryEntityId() + "/" + task.deliveryDate() + "/" + task.newFileName();
        return prefix.isBlank() ? relative : prefix + "/" + relative;
    }

    public String ftpRelativePath(DeliveryTask task) {
        validatePackageName(task.newFileName());
        return task.deliveryId() + "/" + task.deliveryDate() + "/entity-" + task.deliveryEntityId() + "/" + task.newFileName();
    }

    public static void validatePackageName(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")
                || name.contains("..") || Path.of(name).isAbsolute()) {
            throw new IllegalArgumentException("Invalid delivery file name");
        }
    }
}
