package com.powerjob.archive.server.service.ingestmonitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WeComNotifierTest {
    @Test void refusesNonWeComEndpointsWithoutEchoingSecret() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> WeComNotifier.validateAddress("https://example.org/cgi-bin/webhook/send?key=private-key"));
        assertFalse(error.getMessage().contains("private-key"));
    }

    @Test void http200WithBusinessErrorIsNotAcknowledged() throws Exception { // 微信 HTTP 200 的业务错误不能作为已发送依据。
        var settings = new MonitorSettings();
        settings.setWebhookUrl("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test-key");
        settings.setDashboardUrl("http://127.0.0.1:8090/monitor");
        var client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"errcode\":93000}");
        var notifier = new WeComNotifier(settings, new ObjectMapper(), client);
        assertThrows(IllegalStateException.class, () -> notifier.send("{\"msgtype\":\"template_card\",\"template_card\":{\"card_action\":{\"type\":1,\"url\":\"http://127.0.0.1:8090/monitor\"}}}"));
        when(response.body()).thenReturn("{\"errcode\":0}");
        assertDoesNotThrow(() -> notifier.send("{\"msgtype\":\"template_card\",\"template_card\":{\"card_action\":{\"type\":1,\"url\":\"http://127.0.0.1:8090/monitor\"}}}"));
    }
}
