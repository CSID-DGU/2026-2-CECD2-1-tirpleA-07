package com.capstone.triplea.advertisement.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 광고 처리 결과를 Discord로 알림 (발행·거절·실패)
// 시작·생성·심의 알림은 agent가 보냄
@Slf4j
@Component
public class DiscordNotifier {

    private static final int COLOR_PUBLISHED = 7855479;
    private static final int COLOR_REJECTED = 15548997;
    private static final int COLOR_FAILED = 15105570;   // 주황 (거절과 구분)
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final String webhookUrl;

    public DiscordNotifier(
            WebClient.Builder webClientBuilder,
            @Value("${discord.webhook-url}") String webhookUrl) {
        this.webClient = webClientBuilder.build();
        this.webhookUrl = webhookUrl;
    }

    public void notifyPublished(Long eventId, String title, String postUrl) {
        send("📢 SNS Publishing Completed", "- eventId: " + eventId + "\n\n" + title, postUrl, COLOR_PUBLISHED);
    }

    public void notifyRejected(Long eventId, String reason) {
        send("🚨 Content Rejected", "- eventId: " + eventId + "\n\n" + reason, null, COLOR_REJECTED);
    }

    public void notifyFailed(Long eventId, String stage, String message) {
        send("❌ Advertisement Failed", "- eventId: " + eventId + "\n- 실패 단계: " + stage + "\n\n" + message, null, COLOR_FAILED);
    }

    // 전송 실패는 기록만 하고 광고 처리 결과에 영향을 주지 않음
    private void send(String title, String description, String url, int color) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        Map<String, Object> embed = new HashMap<>();
        embed.put("title", title);
        embed.put("description", description);
        embed.put("url", url);
        embed.put("color", color);
        embed.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString());

        try {
            webClient.post()
                    .uri(webhookUrl)
                    .bodyValue(Map.of("embeds", List.of(embed)))
                    .retrieve()
                    .toBodilessEntity()
                    .block(TIMEOUT);
        } catch (Exception e) {
            log.warn("[Discord 전송 실패] {}: {}", title, e.getMessage());
        }
    }
}
