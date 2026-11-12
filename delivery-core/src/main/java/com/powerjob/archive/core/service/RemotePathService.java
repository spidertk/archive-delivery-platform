package com.powerjob.archive.core.service;

import com.powerjob.archive.core.domain.DeliveryTask;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;

@Service
public class RemotePathService {
    /** 模板支持厂商、实体业务账户和批次路径；普通前缀保留本地验收的隔离层级。 */
    public String objectKey(String configuredPrefix, DeliveryTask task, String vendorCode, String accountName, String deliveryBatch) {
        validatePackageName(task.newFileName());
        String prefix = configuredPrefix == null ? "" : configuredPrefix.replaceAll("^/+|/+$", "");
        boolean templated = prefix.contains("{");
        validateSegment(deliveryBatch, "deliveryBatch");
        String directory = templated ? expand(prefix, task, vendorCode, accountName, deliveryBatch)
                : (prefix.isBlank() ? "" : prefix + "/") + deliveryBatch + "/" + task.deliveryId()
                + "/entity-" + task.deliveryEntityId() + "/" + task.deliveryDate();
        validateRelative(directory);
        return directory.isBlank() ? task.newFileName() : directory + "/" + task.newFileName();
    }

    public String ftpRelativePath(String template, DeliveryTask task, String vendorCode, String accountName, String deliveryBatch) {
        validatePackageName(task.newFileName());
        String directory = expand(template, task, vendorCode, accountName, deliveryBatch);
        validateRelative(directory);
        return directory + "/" + task.newFileName();
    }

    private String expand(String template, DeliveryTask task, String vendorCode, String accountName, String deliveryBatch) {
        if (template == null || template.isBlank()) throw new IllegalArgumentException("Remote path template is required");
        Map<String, String> values = Map.of("deliveryId", Long.toString(task.deliveryId()),
                "deliveryEntityId", Long.toString(task.deliveryEntityId()), "deliveryDate", task.deliveryDate().toString(),
                "providerCode", vendorCode, "accountName", accountName, "entityCode", task.entityCode(),
                "deliveryBatch", deliveryBatch);
        String result = template;
        for (Map.Entry<String, String> value : values.entrySet()) {
            validateSegment(value.getValue(), value.getKey());
            result = result.replace("{" + value.getKey() + "}", value.getValue());
        }
        if (result.contains("{") || result.contains("}")) throw new IllegalArgumentException("Unknown remote path placeholder");
        return result.replaceAll("^/+|/+$", "");
    }

    private static void validateSegment(String value, String name) {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("\\") || value.contains("..")
                || value.contains("?") || value.contains("#")) throw new IllegalArgumentException("Unsafe remote path value: " + name);
    }

    private static void validateRelative(String value) {
        if (value.contains("..") || value.contains("?") || value.contains("#") || value.contains("\\")
                || Path.of(value).isAbsolute()) throw new IllegalArgumentException("Unsafe remote path");
    }

    public static void validatePackageName(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")
                || name.contains("..") || Path.of(name).isAbsolute()) {
            throw new IllegalArgumentException("Invalid delivery file name");
        }
    }
}
