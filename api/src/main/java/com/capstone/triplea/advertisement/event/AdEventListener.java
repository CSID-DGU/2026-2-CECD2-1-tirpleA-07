package com.capstone.triplea.advertisement.event;

import com.capstone.triplea.advertisement.AdvertisementService;
import com.capstone.triplea.advertisement.AttemptResult;
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

// 광고 처리 흐름: 생성(agent) -> 발행(Facebook)
// 외부 호출은 트랜잭션 밖에서 하고, 앞뒤 상태는 AdvertisementService가 짧은 트랜잭션으로 저장
@Slf4j
@Component
@RequiredArgsConstructor
public class AdEventListener {

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
                    .block();
            if (response == null || response.status() == null) {
                throw new IllegalStateException("agent 응답 없음");
            }
        } catch (Exception e) {
            String message = describe(e);
            advertisementService.failGenerate(request.attemptId(), message);
            discordNotifier.notifyFailed(eventId, "AGENT_CALL", message);
            return;
        }

        AttemptResult result = advertisementService.completeGenerate(request.attemptId(), response);
        switch (result) {
            case SUCCESS -> publish(eventId);
            case REJECTED -> discordNotifier.notifyRejected(eventId, response.review().reason());
            default -> discordNotifier.notifyFailed(eventId,
                    response.failedStage() == null ? "AGENT_CALL" : response.failedStage().name(),
                    response.errorMessage() == null ? "승인 응답에 콘텐츠 없음" : response.errorMessage());
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
