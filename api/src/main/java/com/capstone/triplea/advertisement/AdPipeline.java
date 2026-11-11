package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.client.DiscordNotifier;
import com.capstone.triplea.advertisement.client.FacebookPublisher;
import com.capstone.triplea.advertisement.dto.AgentGenerateRequestDto;
import com.capstone.triplea.advertisement.dto.AgentGenerateResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Optional;

// 광고 처리 흐름: 생성(agent) -> 발행(Facebook)
// 외부 호출은 트랜잭션 밖에서 하고, 앞뒤 상태는 AdvertisementService가 짧은 트랜잭션으로 저장
// 최초 이벤트, 복구 스케줄러, 수동 재시도가 같은 흐름을 쓰고, 호출한 스레드에서 끝까지 실행
@Slf4j
@Component
@RequiredArgsConstructor
public class AdPipeline {

    // agent 응답 대기 시간 (생성 + 5회 투표 심의). 넘으면 AGENT_CALL 실패로 기록
    private static final Duration AGENT_TIMEOUT = Duration.ofSeconds(120);

    private final WebClient agentWebClient;
    private final AdvertisementService advertisementService;
    private final FacebookPublisher facebookPublisher;
    private final DiscordNotifier discordNotifier;

    // 생성 시도. 승인되면 같은 trigger로 발행까지 이어서 실행
    // 시도를 시작할 수 없으면(이벤트가 PENDING이 아님, 진행 중인 시도 있음, 선점 충돌) 예외
    public void generate(Long eventId, AttemptTrigger trigger) {
        AgentGenerateRequestDto request = advertisementService.startGenerate(eventId, trigger);

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
            failGenerate(eventId, request.attemptId(), describe(e));
            return;
        }

        // 응답 저장 실패(필수 값 누락, 길이 초과 등)는 agent 응답 문제로 보고 실패로 기록
        Optional<AdvertisementService.GenerateResult> completed;
        try {
            completed = advertisementService.completeGenerate(request.attemptId(), response);
        } catch (Exception e) {
            failGenerate(eventId, request.attemptId(), "응답 저장 실패: " + describe(e));
            return;
        }
        // 이미 닫힌 시도에 늦게 도착한 응답은 버림
        if (completed.isEmpty()) {
            return;
        }
        AdvertisementService.GenerateResult result = completed.get();

        switch (result.result()) {
            case SUCCESS -> publish(eventId, trigger);
            case REJECTED -> discordNotifier.notifyRejected(eventId, result.reviewReason());
            default -> {
                String stage = result.failedStage() == null ? "-" : result.failedStage().name();
                if (result.eventStatus() == AdEventStatus.FAILED) {
                    discordNotifier.notifyFailed(eventId, stage, result.errorMessage());
                } else {
                    logRetryPending(eventId, stage, result.errorMessage());
                }
            }
        }
    }

    // 발행 시도 (승인된 생성 결과가 있어야 함)
    public void publish(Long eventId, AttemptTrigger trigger) {
        AdvertisementService.PublishTarget target = advertisementService.startPublish(eventId, trigger);

        // 발행 단계의 실패는 모두 FAILED로 기록 (게시됐을 수 있으므로 재발행 전 게시 여부 확인 필요)
        String postUrl;
        try {
            postUrl = facebookPublisher.publish(target.content());
        } catch (Exception e) {
            failPublish(eventId, target.attemptId(), describe(e));
            return;
        }

        // 게시 후 저장 실패는 게시글을 추적할 수 있도록 URL을 메시지에 남김
        boolean saved;
        try {
            saved = advertisementService.completePublish(target.attemptId(), target.content(), postUrl);
        } catch (Exception e) {
            // 실패 기록까지 실패해도 게시글을 찾을 수 있도록 URL을 먼저 로그에 남김
            log.error("[게시 후 저장 실패] eventId={}, attemptId={}, url={}", eventId, target.attemptId(), postUrl, e);
            failPublish(eventId, target.attemptId(), "게시 후 저장 실패 (url=" + postUrl + "): " + describe(e));
            return;
        }
        if (saved) {
            discordNotifier.notifyPublished(eventId, target.content().title(), postUrl);
        }
    }

    private void failGenerate(Long eventId, Long attemptId, String message) {
        report(eventId, "AGENT_CALL", message, advertisementService.failGenerate(attemptId, message));
    }

    private void failPublish(Long eventId, Long attemptId, String message) {
        report(eventId, "PUBLISH", message, advertisementService.failPublish(attemptId, message));
    }

    // 실패 알림은 자동 재시도를 모두 써서 이벤트가 FAILED가 됐을 때만 보냄
    private void report(Long eventId, String stage, String message, CloseResult closed) {
        switch (closed) {
            case EVENT_FAILED -> discordNotifier.notifyFailed(eventId, stage, message);
            case RETRY_PENDING -> logRetryPending(eventId, stage, message);
            case DISCARDED -> { }
        }
    }

    private void logRetryPending(Long eventId, String stage, String message) {
        log.warn("[시도 실패, 자동 재시도 대기] eventId={}, stage={}, message={}", eventId, stage, message);
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
