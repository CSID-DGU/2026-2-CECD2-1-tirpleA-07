package com.capstone.triplea.advertisement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdAttemptTest {

    @Test
    void 시작하면_열린_시도다() {
        AdAttempt attempt = AdAttempt.start(null, AttemptType.GENERATE, AttemptTrigger.AUTO);

        assertThat(attempt.isOpen()).isTrue();
        assertThat(attempt.getResult()).isNull();
    }

    @Test
    void 실패로_닫으면_실패_단계와_사유가_남는다() {
        AdAttempt attempt = AdAttempt.start(null, AttemptType.GENERATE, AttemptTrigger.AUTO);

        attempt.fail(FailedStage.AGENT_CALL, "timeout");

        assertThat(attempt.isOpen()).isFalse();
        assertThat(attempt.getResult()).isEqualTo(AttemptResult.FAILED);
        assertThat(attempt.getFailedStage()).isEqualTo(FailedStage.AGENT_CALL);
        assertThat(attempt.getErrorMessage()).isEqualTo("timeout");
        assertThat(attempt.getFinishedAt()).isNotNull();
    }

    @Test
    void 사유가_길면_1000자로_자른다() {
        AdAttempt attempt = AdAttempt.start(null, AttemptType.PUBLISH, AttemptTrigger.AUTO);

        attempt.unknown("가".repeat(1500));

        assertThat(attempt.getErrorMessage()).hasSize(1000);
    }

    @Test
    void 이미_닫힌_시도는_다시_닫을_수_없다() {
        AdAttempt attempt = AdAttempt.start(null, AttemptType.GENERATE, AttemptTrigger.AUTO);
        attempt.succeed();

        assertThatThrownBy(attempt::succeed).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 거절은_GENERATE만_불확실은_PUBLISH만_가능하다() {
        AdAttempt publish = AdAttempt.start(null, AttemptType.PUBLISH, AttemptTrigger.AUTO);
        AdAttempt generate = AdAttempt.start(null, AttemptType.GENERATE, AttemptTrigger.AUTO);

        assertThatThrownBy(publish::reject).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> generate.unknown("timeout")).isInstanceOf(IllegalStateException.class);
    }
}
