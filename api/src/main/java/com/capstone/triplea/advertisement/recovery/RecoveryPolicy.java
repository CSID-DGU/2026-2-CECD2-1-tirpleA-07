package com.capstone.triplea.advertisement.recovery;

import com.capstone.triplea.advertisement.AttemptResult;
import com.capstone.triplea.advertisement.AttemptTrigger;
import com.capstone.triplea.advertisement.AttemptType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

// PENDING 이벤트의 다음 할 일과 자동 재시도 소진 여부를 시도 기록으로 판단
// 넘겨받은 값만 쓰고 DB와 현재 시각을 직접 읽지 않음
@Slf4j
@Component
@RequiredArgsConstructor
public class RecoveryPolicy {

    private final AdRecoveryProperties properties;

    // attempts는 시작 순서(started_at, id) 오름차순
    public NextAction decide(LocalDateTime eventUpdatedAt, List<AttemptView> attempts, LocalDateTime now) {
        // 시도 없음: 큐에서 대기 중이거나 재시작으로 작업이 사라짐
        if (attempts.isEmpty()) {
            return isDue(eventUpdatedAt, properties.staleAfter(), now) ? NextAction.GENERATE : NextAction.WAIT;
        }

        AttemptView latest = attempts.get(attempts.size() - 1);

        // 열린 시도: 외부 호출 중이거나 결과를 기록하지 못함
        if (latest.isOpen()) {
            return isDue(latest.startedAt(), properties.staleAfter(), now) ? NextAction.CLOSE_TIMEOUT : NextAction.WAIT;
        }

        if (latest.result() == AttemptResult.FAILED) {
            if (isExhausted(attempts, latest.type())) {
                return NextAction.FAIL_EVENT;
            }
            // 생성 실패는 agent에 부수효과가 없어 다음 주기에 바로 다시 생성
            if (latest.type() == AttemptType.GENERATE) {
                return NextAction.GENERATE;
            }
            // 발행 실패는 게시됐을 수 있으므로 피드에 반영될 시간을 두고 다시 시도
            return isDue(latest.finishedAt(), properties.publishCheckDelay(), now) ? NextAction.PUBLISH : NextAction.WAIT;
        }

        // 심의 통과 후 발행이 시작되지 않음
        if (latest.type() == AttemptType.GENERATE && latest.result() == AttemptResult.SUCCESS) {
            return isDue(latest.finishedAt(), properties.staleAfter(), now) ? NextAction.PUBLISH : NextAction.WAIT;
        }

        // 거절이나 발행 성공은 이벤트 상태와 같은 트랜잭션에서 바뀌므로 PENDING으로 남을 수 없는 조합
        log.warn("[판단 불가] PENDING 이벤트의 최신 시도가 종료 상태: attemptId={}, type={}, result={}",
                latest.id(), latest.type(), latest.result());
        return NextAction.WAIT;
    }

    // 같은 단계의 FAILED 시도가 허용 횟수(최초 1회 + 자동 재시도)에 도달했는지
    // 수동 재시도를 했으면 마지막 MANUAL 시도부터 셈
    public boolean isExhausted(List<AttemptView> attempts, AttemptType type) {
        int from = 0;
        for (int i = attempts.size() - 1; i >= 0; i--) {
            if (attempts.get(i).trigger() == AttemptTrigger.MANUAL) {
                from = i;
                break;
            }
        }
        long failed = attempts.subList(from, attempts.size()).stream()
                .filter(attempt -> attempt.type() == type && attempt.result() == AttemptResult.FAILED)
                .count();
        return failed >= maxAttempts(type);
    }

    // 게시 여부 확인 없이 다시 발행하면 중복 게시될 수 있어, 발행 재시도가 꺼져 있으면 1회만 허용
    private int maxAttempts(AttemptType type) {
        if (type == AttemptType.PUBLISH && !properties.publishRetryEnabled()) {
            return 1;
        }
        return 1 + properties.maxAutoRetries();
    }

    private static boolean isDue(LocalDateTime base, Duration wait, LocalDateTime now) {
        return !base.plus(wait).isAfter(now);
    }
}
