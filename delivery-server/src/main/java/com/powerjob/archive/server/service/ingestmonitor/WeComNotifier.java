package com.powerjob.archive.server.service.ingestmonitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class WeComNotifier {
    private final MonitorSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public WeComNotifier(MonitorSettings settings, ObjectMapper mapper) {
        this(settings, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    WeComNotifier(MonitorSettings settings, ObjectMapper mapper, HttpClient http) {
        this.settings = settings;
        this.mapper = mapper;
        this.http = http;
    }

    static void validateAddress(String address) {
        // 固定企业微信域名和路径，禁止重定向将机器人 key 带到其他站点。
        try {
            URI uri = URI.create(address);
            if (!"https".equals(uri.getScheme()) || !"qyapi.weixin.qq.com".equals(uri.getHost())
                    || !"/cgi-bin/webhook/send".equals(uri.getPath()) || uri.getUserInfo() != null
                    || uri.getPort() != -1 || uri.getFragment() != null || uri.getQuery() == null
                    || !uri.getQuery().matches("key=[A-Za-z0-9-]+")) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("A valid controlled WeCom webhook is required");
        }
    }

    public void send(String payload) throws Exception {
        // HTTP 200 不代表播报成功，必须核对 errcode；调用方只记录固定错误码，不传播含 key 的异常。
        validateAddress(settings.getWebhookUrl());
        var message=mapper.readTree(payload);
        settings.validateDashboard();
        if (!message.path("msgtype").asText().equals("template_card")
                || !message.path("template_card").path("card_action").path("url").asText().equals(settings.getDashboardUrl())) {
            throw new IllegalArgumentException("Only controlled monitor cards can be delivered");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.getWebhookUrl())).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int code = mapper.readTree(response.body()).path("errcode").asInt(-1);
        if (response.statusCode() != 200 || code != 0) throw new DeliveryFailure(response.statusCode(), code);
    }

    static final class DeliveryFailure extends IllegalStateException {
        final int httpStatus;
        final int businessCode;
        DeliveryFailure(int httpStatus, int businessCode) {
            super("WeCom delivery was not acknowledged");
            this.httpStatus = httpStatus;
            this.businessCode = businessCode;
        }
    }

}
