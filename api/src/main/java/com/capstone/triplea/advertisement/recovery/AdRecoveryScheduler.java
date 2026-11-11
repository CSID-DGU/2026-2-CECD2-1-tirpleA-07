package com.capstone.triplea.advertisement.recovery;

import com.capstone.triplea.advertisement.AdPipeline;
import com.capstone.triplea.advertisement.AttemptTrigger;
import com.capstone.triplea.advertisement.CloseResult;
import com.capstone.triplea.advertisement.client.DiscordNotifier;
import com.capstone.triplea.advertisement.recovery.AdRecoveryService.Failure;
import com.capstone.triplea.advertisement.recovery.AdRecoveryService.RecoveryDecision;
import com.capstone.triplea.advertisement.recovery.AdRecoveryService.StaleClose;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

// PENDING 이벤트를 주기적으로 훑어 자동 재시도와 복구를 실행
// 한 건씩 순서대로 끝까지 처리하고(fixedDelay라 주기끼리 겹치지 않음), 시도는 RECOVERY로 기록
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ad.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class AdRecoveryScheduler {

    private final AdRecoveryService recoveryService;
    private final AdPipeline adPipeline;
    private final DiscordNotifier discordNotifier;
    private final AdRecoveryProperties properties;

    @Scheduled(
            initialDelayString = "${ad.recovery.interval:1m}",
            fixedDelayString = "${ad.recovery.interval:1m}"
    )
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        for (Long eventId : recoveryService.findPendingEventIds(properties.batchSize())) {
            try {
                recover(eventId, now);
            } catch (Exception e) {
                // 시도 시작 거부(선점 충돌, 진행 중인 시도 있음)는 다른 쪽이 처리 중이라는 뜻
                log.warn("[복구 건너뜀] eventId={}, {}", eventId, e.toString());
            }
        }
    }

    void recover(Long eventId, LocalDateTime now) {
        RecoveryDecision decision = recoveryService.decideNext(eventId, now);

        // 오래 열려 있는 시도를 닫고, 재시도가 남았으면 같은 주기에 이어서 판단
        if (decision.action() == NextAction.CLOSE_TIMEOUT) {
            StaleClose closed = recoveryService.closeStaleAttempt(decision.openAttemptId(), now);
            log.info("[복구] eventId={}, action=CLOSE_TIMEOUT, attemptId={}, closed={}",
                    eventId, decision.openAttemptId(), closed.result());
            if (closed.result() == CloseResult.EVENT_FAILED) {
                notifyFailed(eventId, closed.failure());
            }
            if (closed.result() != CloseResult.RETRY_PENDING) {
                return;
            }
            decision = recoveryService.decideNext(eventId, now);
        }

        switch (decision.action()) {
            case GENERATE -> {
                log.info("[복구] eventId={}, action=GENERATE", eventId);
                adPipeline.generate(eventId, AttemptTrigger.RECOVERY);
            }
            case PUBLISH -> {
                log.info("[복구] eventId={}, action=PUBLISH", eventId);
                adPipeline.publish(eventId, AttemptTrigger.RECOVERY);
            }
            case FAIL_EVENT -> recoveryService.failExhaustedEvent(eventId)
                    .ifPresent(failure -> notifyFailed(eventId, failure));
            default -> { }
        }
    }

    private void notifyFailed(Long eventId, Failure failure) {
        discordNotifier.notifyFailed(eventId,
                failure.stage() == null ? "-" : failure.stage().name(),
                failure.message());
    }
}
