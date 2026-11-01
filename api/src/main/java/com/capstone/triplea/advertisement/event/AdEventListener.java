package com.capstone.triplea.advertisement.event;

import com.capstone.triplea.advertisement.AdvertisementService;
import com.capstone.triplea.advertisement.client.DiscordNotifier;
import com.capstone.triplea.advertisement.client.FacebookPublisher;
import com.capstone.triplea.advertisement.dto.AgentGenerateRequestDto;
import com.capstone.triplea.advertisement.dto.AgentGenerateResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

// 광고 처리 흐름: 생성(agent) -> 발행(Facebook)
// 외부 호출은 트랜잭션 밖에서 하고, 앞뒤 상태는 AdvertisementService가 짧은 트랜잭션으로 저장
@Slf4j
@Component
@RequiredArgsConstructor
public class AdEventListener {

    // agent 응답 대기 시간 (생성 + 5회 투표 심의). 넘으면 AGENT_CALL 실패로 기록
    private static final Duration AGENT_TIMEOUT = Duration.ofSeconds(120);

    private final WebClient agentWebClient;
    private final AdvertisementService advertisementService;
    private final FacebookPublisher facebookPublisher;
    private final DiscordNotifier discordNotifier;

    // 상품 등록·수정 응답에 영향을 주지 않도록 비동기, 커밋 이후에만 실행
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(AdEventCreated adEventCreated) {
        Long eventId = adEventCreated.eventId();
        AgentGenerateRequestDto request = advertisementService.startGenerate(eventId);

        AgentGenerateResponseDto response;
        try {
            response = agentWebClient.post()
                    .uri("")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(AgentGenerateResponseDto.class)
                    .block(AGENT_TIMEOUT);
            if (response == null || response.status() == null) {
                throw new IllegalStateException("agent 응답 없음");
            }
        } catch (Exception e) {
            String message = describe(e);
            advertisementService.failGenerate(request.attemptId(), message);
            discordNotifier.notifyFailed(eventId, "AGENT_CALL", message);
            return;
        }

        // 응답 저장 실패(필수 값 누락, 길이 초과 등)는 agent 응답 문제로 보고 실패로 기록
        AdvertisementService.GenerateResult result;
        try {
            result = advertisementService.completeGenerate(request.attemptId(), response);
        } catch (Exception e) {
            String message = "응답 저장 실패: " + describe(e);
            advertisementService.failGenerate(request.attemptId(), message);
            discordNotifier.notifyFailed(eventId, "AGENT_CALL", message);
            return;
        }

        switch (result.result()) {
            case SUCCESS -> publish(eventId);
            case REJECTED -> discordNotifier.notifyRejected(eventId, result.reviewReason());
            default -> discordNotifier.notifyFailed(eventId,
                    result.failedStage() == null ? "-" : result.failedStage().name(),
                    result.errorMessage());
        }
    }

    private void publish(Long eventId) {
        AdvertisementService.PublishTarget target = advertisementService.startPublish(eventId);

        // Facebook 호출 실패만 발행 실패로 기록 (FAILED = 게시되지 않음)
        String postUrl;
        try {
            postUrl = facebookPublisher.publish(target.content());
        } catch (Exception e) {
            String message = describe(e);
            advertisementService.failPublish(target.attemptId(), message);
            discordNotifier.notifyFailed(eventId, "PUBLISH", message);
            return;
        }

        // 게시 이후의 저장 실패는 FAILED로 기록하지 않음 (게시됐으므로 시도가 열린 채 남음)
        advertisementService.completePublish(target.attemptId(), target.content(), postUrl);
        discordNotifier.notifyPublished(eventId, target.content().title(), postUrl);
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
