package com.capstone.triplea.advertisement.event;

import com.capstone.triplea.advertisement.AdPipeline;
import com.capstone.triplea.advertisement.AttemptTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 상품 커밋 직후 광고 처리를 시작 (최초 시도)
@Slf4j
@Component
@RequiredArgsConstructor
public class AdEventListener {

    private final AdPipeline adPipeline;

    // 상품 등록·수정 응답에 영향을 주지 않도록 비동기, 커밋 이후에만 실행
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(AdEventCreated adEventCreated) {
        Long eventId = adEventCreated.eventId();
        try {
            adPipeline.generate(eventId, AttemptTrigger.AUTO);
        } catch (OptimisticLockingFailureException e) {
            // 다른 쪽이 같은 이벤트를 먼저 선점함
            log.warn("[광고 처리 건너뜀] eventId={}, {}", eventId, e.toString());
        } catch (Exception e) {
            // 여기서 끝나지 못한 건은 PENDING으로 남아 복구 스케줄러가 이어서 처리
            log.error("[광고 처리 중단] eventId={}", eventId, e);
        }
    }
}
