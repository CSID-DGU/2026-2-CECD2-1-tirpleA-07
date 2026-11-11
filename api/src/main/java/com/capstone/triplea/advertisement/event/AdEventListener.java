package com.capstone.triplea.advertisement.event;

import com.capstone.triplea.advertisement.AdPipeline;
import com.capstone.triplea.advertisement.AttemptTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        } catch (Exception e) {
            // 여기서 끝나지 못한 건은 PENDING으로 남음
            log.warn("[광고 처리 중단] eventId={}, {}", eventId, e.toString());
        }
    }
}
