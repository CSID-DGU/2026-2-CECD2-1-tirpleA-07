package com.capstone.triplea.advertisement.recovery;

import com.capstone.triplea.advertisement.AdPipeline;
import com.capstone.triplea.advertisement.AttemptTrigger;
import com.capstone.triplea.advertisement.CloseResult;
import com.capstone.triplea.advertisement.FailedStage;
import com.capstone.triplea.advertisement.client.DiscordNotifier;
import com.capstone.triplea.advertisement.recovery.AdRecoveryService.Failure;
import com.capstone.triplea.advertisement.recovery.AdRecoveryService.RecoveryDecision;
import com.capstone.triplea.advertisement.recovery.AdRecoveryService.StaleClose;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdRecoverySchedulerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 12, 0);
    private static final AdRecoveryProperties PROPERTIES = new AdRecoveryProperties(
            true, Duration.ofMinutes(1), Duration.ofMinutes(5), 3, false, Duration.ofMinutes(5), 20);

    private final AdRecoveryService recoveryService = mock(AdRecoveryService.class);
    private final AdPipeline adPipeline = mock(AdPipeline.class);
    private final DiscordNotifier discordNotifier = mock(DiscordNotifier.class);
    private final AdRecoveryScheduler scheduler =
            new AdRecoveryScheduler(recoveryService, adPipeline, discordNotifier, PROPERTIES);

    private void decide(Long eventId, RecoveryDecision first, RecoveryDecision... next) {
        when(recoveryService.decideNext(eq(eventId), any())).thenReturn(first, next);
    }

    private static RecoveryDecision action(NextAction action) {
        return new RecoveryDecision(action, null);
    }

    @Test
    void 판단_결과대로_실행한다() {
        decide(1L, action(NextAction.GENERATE));
        decide(2L, action(NextAction.PUBLISH));
        decide(3L, action(NextAction.WAIT));

        assertThat(scheduler.recover(1L, NOW)).isTrue();
        assertThat(scheduler.recover(2L, NOW)).isTrue();
        assertThat(scheduler.recover(3L, NOW)).isFalse();

        verify(adPipeline).generate(1L, AttemptTrigger.RECOVERY);
        verify(adPipeline).publish(2L, AttemptTrigger.RECOVERY);
        verify(adPipeline, never()).generate(eq(3L), any());
        verifyNoInteractions(discordNotifier);
    }

    @Test
    void 오래_열린_시도를_닫고_재시도가_남았으면_이어서_실행한다() {
        decide(1L, new RecoveryDecision(NextAction.CLOSE_TIMEOUT, 10L), action(NextAction.GENERATE));
        when(recoveryService.closeStaleAttempt(10L, NOW)).thenReturn(
                new StaleClose(CloseResult.RETRY_PENDING, new Failure(FailedStage.AGENT_CALL, "5분 초과")));

        scheduler.recover(1L, NOW);

        verify(adPipeline).generate(1L, AttemptTrigger.RECOVERY);
        verifyNoInteractions(discordNotifier);
    }

    @Test
    void 닫으면서_이벤트가_실패하면_알림만_보낸다() {
        decide(1L, new RecoveryDecision(NextAction.CLOSE_TIMEOUT, 10L));
        when(recoveryService.closeStaleAttempt(10L, NOW)).thenReturn(
                new StaleClose(CloseResult.EVENT_FAILED, new Failure(FailedStage.PUBLISH, "5분 초과")));

        scheduler.recover(1L, NOW);

        verify(discordNotifier).notifyFailed(1L, "PUBLISH", "5분 초과");
        verifyNoInteractions(adPipeline);
    }

    @Test
    void 소진된_이벤트는_실패로_바꾸고_알림을_보낸다() {
        decide(1L, action(NextAction.FAIL_EVENT));
        when(recoveryService.failExhaustedEvent(1L))
                .thenReturn(Optional.of(new Failure(FailedStage.AGENT_CALL, "연결 실패")));

        scheduler.recover(1L, NOW);

        verify(discordNotifier).notifyFailed(1L, "AGENT_CALL", "연결 실패");
    }

    @Test
    void 한_건의_예외가_다음_건_처리를_막지_않는다() {
        when(recoveryService.findPendingEventIds()).thenReturn(List.of(1L, 2L));
        decide(1L, action(NextAction.GENERATE));
        decide(2L, action(NextAction.GENERATE));
        doThrow(new IllegalStateException("진행 중인 시도 있음")).when(adPipeline).generate(eq(1L), any());

        scheduler.run();

        verify(adPipeline).generate(2L, AttemptTrigger.RECOVERY);
    }

    @Test
    void 기다리는_건은_세지_않고_실행한_건수로_상한을_둔다() {
        AdRecoveryScheduler limited = new AdRecoveryScheduler(recoveryService, adPipeline, discordNotifier,
                new AdRecoveryProperties(true, Duration.ofMinutes(1), Duration.ofMinutes(5), 3, false, Duration.ofMinutes(5), 2));
        when(recoveryService.findPendingEventIds()).thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
        decide(1L, action(NextAction.WAIT));
        decide(2L, action(NextAction.WAIT));
        decide(3L, action(NextAction.GENERATE));
        decide(4L, action(NextAction.GENERATE));
        decide(5L, action(NextAction.GENERATE));

        limited.run();

        verify(adPipeline).generate(3L, AttemptTrigger.RECOVERY);
        verify(adPipeline).generate(4L, AttemptTrigger.RECOVERY);
        verify(adPipeline, never()).generate(eq(5L), any());
    }

    // @Scheduled의 주기 문자열(기본값 1m)을 Spring이 해석할 수 있는지 확인
    @Test
    void 스케줄_주기_문자열을_해석한다() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(SchedulingConfig.class)) {
            assertThat(context.getBean(AdRecoveryScheduler.class)).isNotNull();
        }
    }

    @Configuration
    @EnableScheduling
    static class SchedulingConfig {
        @Bean
        AdRecoveryScheduler adRecoveryScheduler() {
            return new AdRecoveryScheduler(
                    mock(AdRecoveryService.class), mock(AdPipeline.class), mock(DiscordNotifier.class), PROPERTIES);
        }
    }
}
