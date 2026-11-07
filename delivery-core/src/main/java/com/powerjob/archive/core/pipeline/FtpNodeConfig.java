package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** FTP 节点只持有账户到密码的受控映射；主机、目录和账户关系仍来自任务直属交付实体。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FtpNodeConfig(String deliveryEntityFrom, Map<String, String> ftpCredential) {
    public void validate() {
        if (!"TASK".equals(deliveryEntityFrom) || ftpCredential == null || ftpCredential.isEmpty()
                || ftpCredential.values().stream().anyMatch(value -> value == null || value.isBlank()
                || value.contains("replace-") || value.contains("********"))) {
            throw new IllegalArgumentException("FTP task entity credentials are required and cannot be placeholders");
        }
    }

    public String passwordFor(String account) {
        String password = ftpCredential.get(account);
        if (password == null) throw new IllegalArgumentException("No Pipeline FTP credential for delivery account");
        return password;
    }
}
