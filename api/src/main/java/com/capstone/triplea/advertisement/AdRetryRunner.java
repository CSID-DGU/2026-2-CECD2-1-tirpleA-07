package com.capstone.triplea.advertisement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

// 수동 재시도로 시작한 시도를 요청 스레드 밖에서 실행
@Slf4j
@Component
@RequiredArgsConstructor
public class AdRetryRunner {

    private final AdPipeline adPipeline;

    // 재시도 응답에 영향을 주지 않도록 비동기로 실행
    @Async
    public void run(AdRetryService.RetryStarted started) {
        try {
            switch (started) {
                case AdRetryService.RetryStarted.Generate generate ->
                        adPipeline.runGenerate(generate.request(), AttemptTrigger.MANUAL);
                case AdRetryService.RetryStarted.Publish publish ->
                        adPipeline.runPublish(publish.eventId(), publish.target());
            }
        } catch (Exception e) {
            // 여기서 끝나지 못한 건은 열린 시도로 남아 복구 스케줄러가 이어서 처리
            log.error("[수동 재시도 중단] eventId={}, attemptId={}", started.eventId(), started.attemptId(), e);
        }
    }
}
