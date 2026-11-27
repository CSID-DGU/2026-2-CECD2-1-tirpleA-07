package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.AdRetryService.RetryCheck;
import com.capstone.triplea.advertisement.AdRetryService.RetryStarted;
import com.capstone.triplea.advertisement.RetryRejectedException.Code;
import com.capstone.triplea.advertisement.dto.AgentGenerateRequestDto;
import com.capstone.triplea.advertisement.recovery.AdRecoveryProperties;
import com.capstone.triplea.advertisement.recovery.AdRecoveryService;
import com.capstone.triplea.advertisement.recovery.NextAction;
import com.capstone.triplea.advertisement.recovery.RecoveryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

// 수동 재시도: 가능 여부 판단과 시도 시작
@DataJpaTest
@Import({AdvertisementService.class, AdRetryService.class, AdRecoveryService.class, RecoveryPolicy.class,
        AdRetryServiceTest.Config.class})
class AdRetryServiceTest {

    @TestConfiguration
    static class Config {
        @Bean
        AdRecoveryProperties adRecoveryProperties() {
            return new AdRecoveryProperties(
                    true, Duration.ofMinutes(1), Duration.ofMinutes(5), 3, true, Duration.ofMinutes(5), 20);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private TestEntityManager em;
    @Autowired
    private AdvertisementService advertisementService;
    @Autowired
    private AdRetryService retryService;
    @Autowired
    private AdRecoveryService recoveryService;
    @Autowired
    private AdAttemptRepository attemptRepository;

    private Long eventId;

    @BeforeEach
    void setUp() {
        eventId = AdTestFixture.event(em.getEntityManager(), AdTestFixture.product(em.getEntityManager(), "다낭 4박 5일"));
    }

    private AdEvent event() {
        em.flush();
        em.clear();
        return em.find(AdEvent.class, eventId);
    }

    private List<AdAttempt> attempts() {
        em.flush();
        em.clear();
        return attemptRepository.findByEventIdOrderByStartedAtAscIdAsc(eventId);
    }

    // 마지막 발행 실패가 오래전에 끝난 것으로 바꿈
    private void backdateFinishedAt(Long attemptId, Duration ago) {
        em.flush();
        em.getEntityManager()
                .createQuery("update AdAttempt a set a.finishedAt = :finishedAt where a.id = :id")
                .setParameter("finishedAt", LocalDateTime.now().minus(ago))
                .setParameter("id", attemptId)
                .executeUpdate();
        em.clear();
    }

    @Test
    void 생성에서_실패한_이벤트는_생성부터_다시_시작한다() {
        AdTestFixture.failGenerateUntilEventFailed(advertisementService, eventId);
        assertThat(event().getStatus()).isEqualTo(AdEventStatus.FAILED);

        RetryStarted started = retryService.start(eventId);

        assertThat(started).isInstanceOf(RetryStarted.Generate.class);
        assertThat(started.attemptType()).isEqualTo(AttemptType.GENERATE);
        assertThat(started.eventId()).isEqualTo(eventId);
        assertThat(event().getStatus()).isEqualTo(AdEventStatus.PENDING);
        List<AdAttempt> attempts = attempts();
        AdAttempt manual = attempts.get(attempts.size() - 1);
        assertThat(attempts).hasSize(5);
        assertThat(manual.getId()).isEqualTo(started.attemptId());
        assertThat(manual.getTriggerType()).isEqualTo(AttemptTrigger.MANUAL);
        assertThat(manual.isOpen()).isTrue();
    }

    @Test
    void 발행에서_실패한_이벤트는_대기_시간이_지나면_발행부터_다시_시작한다() {
        AdTestFixture.approve(advertisementService, eventId, null);
        LocalDateTime firstPublishStartedAt = null;
        Long lastAttemptId = AdTestFixture.failPublishUntilEventFailed(advertisementService, eventId);
        for (AdAttempt attempt : attempts()) {
            if (attempt.getAttemptType() == AttemptType.PUBLISH && firstPublishStartedAt == null) {
                firstPublishStartedAt = attempt.getStartedAt();
            }
        }
        backdateFinishedAt(lastAttemptId, Duration.ofMinutes(6));

        RetryStarted started = retryService.start(eventId);

        assertThat(started).isInstanceOf(RetryStarted.Publish.class);
        RetryStarted.Publish publish = (RetryStarted.Publish) started;
        // 게시 여부 확인은 첫 발행 시도 기준
        assertThat(publish.target().verifySince()).isEqualTo(firstPublishStartedAt);
        assertThat(publish.target().content()).isEqualTo(AdTestFixture.CONTENT);
        List<AdAttempt> attempts = attempts();
        AdAttempt manual = attempts.get(attempts.size() - 1);
        assertThat(manual.getAttemptType()).isEqualTo(AttemptType.PUBLISH);
        assertThat(manual.getTriggerType()).isEqualTo(AttemptTrigger.MANUAL);
        assertThat(event().getStatus()).isEqualTo(AdEventStatus.PENDING);
    }

    @Test
    void 발행_실패_직후에는_대기_중으로_거절하고_가능_시각을_알린다() {
        AdTestFixture.approve(advertisementService, eventId, null);
        Long lastAttemptId = AdTestFixture.failPublishUntilEventFailed(advertisementService, eventId);
        em.flush();
        em.clear();
        LocalDateTime finishedAt = em.find(AdAttempt.class, lastAttemptId).getFinishedAt();

        RetryRejectedException rejected =
                catchThrowableOfType(RetryRejectedException.class, () -> retryService.start(eventId));

        assertThat(rejected.getCode()).isEqualTo(Code.PUBLISH_WAIT);
        assertThat(rejected.getRetryAvailableAt()).isEqualTo(finishedAt.plusMinutes(5));
        // 거절하면 아무것도 바꾸지 않음
        assertThat(event().getStatus()).isEqualTo(AdEventStatus.FAILED);
        assertThat(attempts()).hasSize(5);
    }

    @Test
    void 재시도_가능_여부는_시각에_따라_달라진다() {
        AdTestFixture.approve(advertisementService, eventId, null);
        Long lastAttemptId = AdTestFixture.failPublishUntilEventFailed(advertisementService, eventId);
        em.flush();
        // DB에 저장된 값으로 비교함 (메모리의 시각은 OS에 따라 나노초까지 있어 DB에서 다시 읽은 값과 다를 수 있음)
        em.clear();
        LocalDateTime finishedAt = em.find(AdAttempt.class, lastAttemptId).getFinishedAt();

        RetryCheck before = retryService.check(event(), finishedAt.plusMinutes(4));
        assertThat(before.allowed()).isFalse();
        assertThat(before.rejectedCode()).isEqualTo(Code.PUBLISH_WAIT);
        assertThat(before.availableAt()).isEqualTo(finishedAt.plusMinutes(5));

        // 가능 시각이 되면 바로 가능
        RetryCheck after = retryService.check(event(), finishedAt.plusMinutes(5));
        assertThat(after.allowed()).isTrue();
        assertThat(after.nextType()).isEqualTo(AttemptType.PUBLISH);
        assertThat(after.availableAt()).isNull();
    }

    @Test
    void FAILED가_아닌_이벤트는_거절한다() {
        // PENDING
        assertRejectedAsNotFailed();

        // REJECTED
        AdTestFixture.reject(advertisementService, eventId);
        assertThat(event().getStatus()).isEqualTo(AdEventStatus.REJECTED);
        assertRejectedAsNotFailed();
    }

    @Test
    void PUBLISHED_이벤트는_거절한다() {
        AdTestFixture.approve(advertisementService, eventId, null);
        AdvertisementService.PublishTarget target = advertisementService.startPublish(eventId, AttemptTrigger.AUTO);
        advertisementService.completePublish(target.attemptId(), target.content(), "https://www.facebook.com/111/posts/1");
        assertThat(event().getStatus()).isEqualTo(AdEventStatus.PUBLISHED);

        assertRejectedAsNotFailed();
    }

    private void assertRejectedAsNotFailed() {
        int before = attempts().size();
        assertThatThrownBy(() -> retryService.start(eventId))
                .isInstanceOfSatisfying(RetryRejectedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(Code.NOT_FAILED);
                    assertThat(e.getRetryAvailableAt()).isNull();
                });
        assertThat(attempts()).hasSize(before);
    }

    @Test
    void 없는_이벤트는_찾을_수_없음으로_알린다() {
        assertThatThrownBy(() -> retryService.start(-1L)).isInstanceOf(AdEventNotFoundException.class);
    }

    @Test
    void 재시도_직후_복구_스케줄러는_그_이벤트를_기다린다() {
        AdTestFixture.failGenerateUntilEventFailed(advertisementService, eventId);

        retryService.start(eventId);
        em.flush();
        em.clear();

        // 열린 MANUAL 시도가 있으므로 FAILED로 되돌리지 않음
        assertThat(recoveryService.decideNext(eventId, LocalDateTime.now()).action()).isEqualTo(NextAction.WAIT);
        assertThat(recoveryService.failExhaustedEvent(eventId)).isEmpty();
    }

    @Test
    void 수동_시도가_실패하면_자동_재시도_3회가_다시_주어진다() {
        AdTestFixture.failGenerateUntilEventFailed(advertisementService, eventId);

        RetryStarted started = retryService.start(eventId);
        assertThat(advertisementService.failGenerate(started.attemptId(), "agent 연결 실패"))
                .isEqualTo(CloseResult.RETRY_PENDING);
        for (int i = 1; i <= 2; i++) {
            AgentGenerateRequestDto request = advertisementService.startGenerate(eventId, AttemptTrigger.RECOVERY);
            assertThat(advertisementService.failGenerate(request.attemptId(), "agent 연결 실패"))
                    .isEqualTo(CloseResult.RETRY_PENDING);
        }

        // 수동 1회 + 자동 3회를 모두 실패하면 다시 FAILED
        AgentGenerateRequestDto last = advertisementService.startGenerate(eventId, AttemptTrigger.RECOVERY);
        assertThat(advertisementService.failGenerate(last.attemptId(), "agent 연결 실패"))
                .isEqualTo(CloseResult.EVENT_FAILED);
        assertThat(event().getStatus()).isEqualTo(AdEventStatus.FAILED);
        // 다시 수동 재시도 가능
        assertThat(retryService.check(event(), LocalDateTime.now()).allowed()).isTrue();
    }
}
