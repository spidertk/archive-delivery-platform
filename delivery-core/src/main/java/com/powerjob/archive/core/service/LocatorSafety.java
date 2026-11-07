package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** locator 只允许非 Secret 通用事实；节点返回后由执行内核统一拒绝敏感字段和签名 URL。 */
public final class LocatorSafety {
    private static final Set<String> FORBIDDEN = Set.of("password", "privatekey", "accesskeyid", "accesskeysecret", "ftpcredential");
    private LocatorSafety() {}

    public static void validate(JsonNode locator) {
        if (locator == null || locator.isNull() || locator.isValueNode()) return;
        if (locator.isArray()) {
            locator.forEach(LocatorSafety::validate);
            return;
        }
        if (!locator.isObject()) throw new IllegalArgumentException("Node locator must be an object");
        Iterator<Map.Entry<String, JsonNode>> fields = locator.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (FORBIDDEN.contains(field.getKey().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Secret in node locator");
            if (field.getKey().equals("uri") && field.getValue().asText().contains("?")) throw new IllegalArgumentException("Signed URI in node locator");
            validate(field.getValue());
        }
    }
}
