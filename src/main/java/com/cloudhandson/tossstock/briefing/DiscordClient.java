package com.cloudhandson.tossstock.briefing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Discord 웹훅 전송. content 만 보낸다(2000자 제한은 호출측에서 보장). */
@Component
public class DiscordClient {

    private static final Logger log = LoggerFactory.getLogger(DiscordClient.class);

    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** 전송. 성공 여부 반환(웹훅 미설정/실패 시 false, 예외 던지지 않음). */
    public boolean send(String webhookUrl, String content) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return false;
        }
        try {
            String body = om.writeValueAsString(Map.of("content", content));
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() >= 400) {
                log.warn("Discord 전송 실패 HTTP {}: {}", res.statusCode(), clip(res.body()));
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Discord 전송 예외: {}", e.toString());
            return false;
        }
    }

    private static String clip(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) : s);
    }
}
