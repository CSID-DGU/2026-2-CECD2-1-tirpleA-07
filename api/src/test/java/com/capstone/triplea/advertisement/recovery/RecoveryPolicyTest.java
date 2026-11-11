package com.capstone.triplea.advertisement.recovery;

import com.capstone.triplea.advertisement.AttemptResult;
import com.capstone.triplea.advertisement.AttemptTrigger;
import com.capstone.triplea.advertisement.AttemptType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.capstone.triplea.advertisement.AttemptResult.FAILED;
import static com.capstone.triplea.advertisement.AttemptResult.REJECTED;
import static com.capstone.triplea.advertisement.AttemptResult.SUCCESS;
import static com.capstone.triplea.advertisement.AttemptTrigger.AUTO;
import static com.capstone.triplea.advertisement.AttemptTrigger.MANUAL;
import static com.capstone.triplea.advertisement.AttemptTrigger.RECOVERY;
import static com.capstone.triplea.advertisement.AttemptType.GENERATE;
import static com.capstone.triplea.advertisement.AttemptType.PUBLISH;
import static org.assertj.core.api.Assertions.assertThat;

class RecoveryPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 12, 0, 0);

    private final RecoveryPolicy policy = policy(false);
    private final RecoveryPolicy publishRetryPolicy = policy(true);

    private long nextId = 1;

    private static RecoveryPolicy policy(boolean publishRetryEnabled) {
        return new RecoveryPolicy(new AdRecoveryProperties(
                true, Duration.ofMinutes(1), Duration.ofMinutes(5), 3, publishRetryEnabled, Duration.ofMinutes(5), 20));
    }

    // 종료 시각이 NOW보다 secondsAgo초 전인 닫힌 시도
    private AttemptView closed(AttemptType type, AttemptTrigger trigger, AttemptResult result, long secondsAgo) {
        LocalDateTime finishedAt = NOW.minusSeconds(secondsAgo);
        return new AttemptView(nextId++, type, trigger, result, finishedAt.minusSeconds(10), finishedAt);
    }

    // 시작 시각이 NOW보다 secondsAgo초 전인 열린 시도
    private AttemptView open(AttemptType type, long secondsAgo) {
        return new AttemptView(nextId++, type, AUTO, null, NOW.minusSeconds(secondsAgo), null);
    }

    private List<AttemptView> failures(AttemptType type, int count) {
        List<AttemptView> attempts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            attempts.add(closed(type, i == 0 ? AUTO : RECOVERY, FAILED, 600L - i * 60L));
        }
        return attempts;
    }

    @Test
    void 시도가_없으면_5분_뒤에_생성한다() {
        assertThat(policy.decide(NOW.minusSeconds(299), List.of(), NOW)).isEqualTo(NextAction.WAIT);
        assertThat(policy.decide(NOW.minusSeconds(300), List.of(), NOW)).isEqualTo(NextAction.GENERATE);
    }

    @Test
    void 열린_시도는_5분이_지나면_닫는다() {
        assertThat(policy.decide(NOW, List.of(open(GENERATE, 299)), NOW)).isEqualTo(NextAction.WAIT);
        assertThat(policy.decide(NOW, List.of(open(GENERATE, 300)), NOW)).isEqualTo(NextAction.CLOSE_TIMEOUT);
        assertThat(policy.decide(NOW, List.of(closed(GENERATE, AUTO, SUCCESS, 400), open(PUBLISH, 301)), NOW))
                .isEqualTo(NextAction.CLOSE_TIMEOUT);
    }

    @Test
    void 승인_후_발행이_시작되지_않으면_5분_뒤에_발행한다() {
        assertThat(policy.decide(NOW, List.of(closed(GENERATE, AUTO, SUCCESS, 299)), NOW)).isEqualTo(NextAction.WAIT);
        assertThat(policy.decide(NOW, List.of(closed(GENERATE, AUTO, SUCCESS, 300)), NOW)).isEqualTo(NextAction.PUBLISH);
    }

    @Test
    void 생성_실패는_재시도가_남았으면_바로_다시_생성한다() {
        assertThat(policy.decide(NOW, List.of(closed(GENERATE, AUTO, FAILED, 0)), NOW)).isEqualTo(NextAction.GENERATE);
        assertThat(policy.decide(NOW, failures(GENERATE, 3), NOW)).isEqualTo(NextAction.GENERATE);
    }

    @Test
    void 생성_실패가_4번이면_이벤트를_실패로_바꾼다() {
        assertThat(policy.decide(NOW, failures(GENERATE, 4), NOW)).isEqualTo(NextAction.FAIL_EVENT);
    }

    @Test
    void 발행_재시도가_꺼져_있으면_발행_실패_1번으로_이벤트를_실패로_바꾼다() {
        List<AttemptView> attempts = List.of(closed(GENERATE, AUTO, SUCCESS, 900), closed(PUBLISH, AUTO, FAILED, 600));

        assertThat(policy.decide(NOW, attempts, NOW)).isEqualTo(NextAction.FAIL_EVENT);
    }

    @Test
    void 발행_재시도가_켜져_있으면_실패_5분_뒤에_다시_발행한다() {
        AttemptView generated = closed(GENERATE, AUTO, SUCCESS, 900);

        assertThat(publishRetryPolicy.decide(NOW, List.of(generated, closed(PUBLISH, AUTO, FAILED, 299)), NOW))
                .isEqualTo(NextAction.WAIT);
        assertThat(publishRetryPolicy.decide(NOW, List.of(generated, closed(PUBLISH, AUTO, FAILED, 300)), NOW))
                .isEqualTo(NextAction.PUBLISH);

        List<AttemptView> exhausted = new ArrayList<>(List.of(generated));
        exhausted.addAll(failures(PUBLISH, 4));
        assertThat(publishRetryPolicy.decide(NOW, exhausted, NOW)).isEqualTo(NextAction.FAIL_EVENT);
    }

    @Test
    void PENDING일_수_없는_조합은_기다린다() {
        assertThat(policy.decide(NOW, List.of(closed(GENERATE, AUTO, REJECTED, 900)), NOW)).isEqualTo(NextAction.WAIT);
        assertThat(policy.decide(NOW,
                List.of(closed(GENERATE, AUTO, SUCCESS, 900), closed(PUBLISH, AUTO, SUCCESS, 800)), NOW))
                .isEqualTo(NextAction.WAIT);
    }

    @Test
    void 소진은_같은_단계의_실패만_센다() {
        List<AttemptView> attempts = new ArrayList<>(failures(GENERATE, 3));
        attempts.add(closed(GENERATE, RECOVERY, SUCCESS, 300));
        attempts.add(closed(PUBLISH, RECOVERY, FAILED, 200));

        assertThat(policy.isExhausted(attempts, GENERATE)).isFalse();
        assertThat(publishRetryPolicy.isExhausted(attempts, PUBLISH)).isFalse();
        assertThat(policy.isExhausted(attempts, PUBLISH)).isTrue();
    }

    @Test
    void 수동_재시도_이후에는_마지막_MANUAL_시도부터_센다() {
        List<AttemptView> attempts = new ArrayList<>(failures(GENERATE, 4));
        assertThat(policy.isExhausted(attempts, GENERATE)).isTrue();

        attempts.add(closed(GENERATE, MANUAL, FAILED, 120));
        assertThat(policy.isExhausted(attempts, GENERATE)).isFalse();
        assertThat(policy.decide(NOW, attempts, NOW)).isEqualTo(NextAction.GENERATE);

        attempts.add(closed(GENERATE, RECOVERY, FAILED, 90));
        attempts.add(closed(GENERATE, RECOVERY, FAILED, 60));
        assertThat(policy.isExhausted(attempts, GENERATE)).isFalse();
        attempts.add(closed(GENERATE, RECOVERY, FAILED, 30));
        assertThat(policy.isExhausted(attempts, GENERATE)).isTrue();
    }
}
