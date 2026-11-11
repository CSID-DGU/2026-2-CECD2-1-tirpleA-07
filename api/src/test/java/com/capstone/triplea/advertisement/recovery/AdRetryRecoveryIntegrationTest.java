package com.capstone.triplea.advertisement.recovery;

import com.capstone.triplea.advertisement.AdAttempt;
import com.capstone.triplea.advertisement.AdAttemptRepository;
import com.capstone.triplea.advertisement.AdEvent;
import com.capstone.triplea.advertisement.AdEventStatus;
import com.capstone.triplea.advertisement.AdEventType;
import com.capstone.triplea.advertisement.AdPostRepository;
import com.capstone.triplea.advertisement.AdvertisementService;
import com.capstone.triplea.advertisement.AttemptResult;
import com.capstone.triplea.advertisement.AttemptTrigger;
import com.capstone.triplea.advertisement.AttemptType;
import com.capstone.triplea.advertisement.CloseResult;
import com.capstone.triplea.advertisement.FailedStage;
import com.capstone.triplea.advertisement.dto.AgentGenerateRequestDto;
import com.capstone.triplea.advertisement.dto.AgentGenerateResponseDto;
import com.capstone.triplea.advertisement.value.AdContent;
import com.capstone.triplea.advertisement.value.ProductSnapshot;
import com.capstone.triplea.product.Product;
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

@DataJpaTest
@Import({AdvertisementService.class, AdRecoveryService.class, RecoveryPolicy.class,
        AdRetryRecoveryIntegrationTest.Config.class})
class AdRetryRecoveryIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        AdRecoveryProperties adRecoveryProperties() {
            return new AdRecoveryProperties(
                    true, Duration.ofMinutes(1), Duration.ofMinutes(5), 3, false, Duration.ofMinutes(5), 20);
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
    private AdRecoveryService recoveryService;
    @Autowired
    private AdAttemptRepository adAttemptRepository;
    @Autowired
    private AdPostRepository adPostRepository;

    private Long eventId;

    @BeforeEach
    void setUp() {
        Product product = em.persist(
                Product.builder().name("다낭 4박 5일").listPrice(1000).price(1000).quantity(10).build());
        AdEvent event = em.persist(AdEvent.pending(product, AdEventType.NEW,
                ProductSnapshot.ofNew(ProductSnapshot.ProductInfo.from(product))));
        eventId = event.getId();
    }

    private AdEventStatus eventStatus() {
        em.flush();
        em.clear();
        return em.find(AdEvent.class, eventId).getStatus();
    }

    private List<AdAttempt> attempts() {
        return adAttemptRepository.findByEventIdOrderByStartedAtAscIdAsc(eventId);
    }

    private static AgentGenerateResponseDto approved(Long attemptId) {
        AdContent content = new AdContent("제목", "본문", "지금 예약", List.of("#다낭"), null);
        return new AgentGenerateResponseDto(attemptId, AgentGenerateResponseDto.Status.APPROVED, null, null,
                content, content, new AgentGenerateResponseDto.Review("문제 없음", 0, null, List.of()));
    }

    private static AgentGenerateResponseDto failed(Long attemptId) {
        return new AgentGenerateResponseDto(attemptId, AgentGenerateResponseDto.Status.FAILED,
                FailedStage.REVIEW, "심의 응답 파싱 실패", null, null, null);
    }

    @Test
    void 생성_실패는_4번째에서만_이벤트를_실패로_바꾼다() {
        for (int i = 1; i <= 3; i++) {
            AttemptTrigger trigger = i == 1 ? AttemptTrigger.AUTO : AttemptTrigger.RECOVERY;
            AgentGenerateRequestDto request = advertisementService.startGenerate(eventId, trigger);
            assertThat(advertisementService.failGenerate(request.attemptId(), "연결 실패"))
                    .isEqualTo(CloseResult.RETRY_PENDING);
            assertThat(eventStatus()).isEqualTo(AdEventStatus.PENDING);
        }

        AgentGenerateRequestDto last = advertisementService.startGenerate(eventId, AttemptTrigger.RECOVERY);
        assertThat(advertisementService.failGenerate(last.attemptId(), "연결 실패")).isEqualTo(CloseResult.EVENT_FAILED);
        assertThat(eventStatus()).isEqualTo(AdEventStatus.FAILED);

        assertThat(attempts()).hasSize(4)
                .allMatch(attempt -> attempt.getResult() == AttemptResult.FAILED)
                .extracting(AdAttempt::getTriggerType)
                .containsExactly(AttemptTrigger.AUTO, AttemptTrigger.RECOVERY, AttemptTrigger.RECOVERY, AttemptTrigger.RECOVERY);
    }

    @Test
    void agent가_실패를_응답해도_재시도가_남으면_PENDING이다() {
        AgentGenerateRequestDto request = advertisementService.startGenerate(eventId, AttemptTrigger.AUTO);

        AdvertisementService.GenerateResult result =
                advertisementService.completeGenerate(request.attemptId(), failed(request.attemptId())).orElseThrow();

        assertThat(result.result()).isEqualTo(AttemptResult.FAILED);
        assertThat(result.failedStage()).isEqualTo(FailedStage.REVIEW);
        assertThat(result.eventStatus()).isEqualTo(AdEventStatus.PENDING);
        assertThat(eventStatus()).isEqualTo(AdEventStatus.PENDING);
    }

    @Test
    void 발행_실패는_1번으로_이벤트를_실패로_바꾼다() {
        AgentGenerateRequestDto request = advertisementService.startGenerate(eventId, AttemptTrigger.AUTO);
        advertisementService.completeGenerate(request.attemptId(), approved(request.attemptId()));
        AdvertisementService.PublishTarget target = advertisementService.startPublish(eventId, AttemptTrigger.AUTO);

        assertThat(advertisementService.failPublish(target.attemptId(), "timeout")).isEqualTo(CloseResult.EVENT_FAILED);
        assertThat(eventStatus()).isEqualTo(AdEventStatus.FAILED);
    }

    @Test
    void 닫힌_시도에_온_결과는_버린다() {
        AgentGenerateRequestDto request = advertisementService.startGenerate(eventId, AttemptTrigger.AUTO);
        Long attemptId = request.attemptId();
        advertisementService.failGenerate(attemptId, "timeout");

        assertThat(advertisementService.completeGenerate(attemptId, approved(attemptId))).isEmpty();
        assertThat(advertisementService.failGenerate(attemptId, "다른 실패")).isEqualTo(CloseResult.DISCARDED);
        assertThat(advertisementService.failPublish(attemptId, "다른 실패")).isEqualTo(CloseResult.DISCARDED);
        assertThat(advertisementService.completePublish(attemptId,
                new AdContent("제목", "본문", "cta", List.of(), null), "https://www.facebook.com/1/posts/2")).isFalse();

        em.flush();
        em.clear();
        AdAttempt attempt = em.find(AdAttempt.class, attemptId);
        assertThat(attempt.getResult()).isEqualTo(AttemptResult.FAILED);
        assertThat(attempt.getErrorMessage()).isEqualTo("timeout");
        assertThat(adPostRepository.findByEventId(eventId)).isEmpty();
        assertThat(eventStatus()).isEqualTo(AdEventStatus.PENDING);
    }

    @Test
    void 승인된_생성이_있으면_다시_생성하지_않는다() {
        AgentGenerateRequestDto request = advertisementService.startGenerate(eventId, AttemptTrigger.AUTO);
        advertisementService.completeGenerate(request.attemptId(), approved(request.attemptId()));

        assertThatThrownBy(() -> advertisementService.startGenerate(eventId, AttemptTrigger.RECOVERY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 시도가_없는_이벤트는_5분_뒤에_생성_대상이다() {
        LocalDateTime now = LocalDateTime.now();

        assertThat(recoveryService.decideNext(eventId, now).action()).isEqualTo(NextAction.WAIT);
        assertThat(recoveryService.decideNext(eventId, now.plusMinutes(6)).action()).isEqualTo(NextAction.GENERATE);
        assertThat(recoveryService.findPendingEventIds()).containsExactly(eventId);
    }

    @Test
    void 오래_열린_생성_시도를_닫으면_바로_재생성_대상이_된다() {
        Long attemptId = advertisementService.startGenerate(eventId, AttemptTrigger.AUTO).attemptId();
        em.flush();
        LocalDateTime now = LocalDateTime.now();

        // 아직 5분 전이면 닫지 않음
        assertThat(recoveryService.closeStaleAttempt(attemptId, now.plusMinutes(4)).result())
                .isEqualTo(CloseResult.DISCARDED);

        LocalDateTime later = now.plusMinutes(6);
        AdRecoveryService.RecoveryDecision decision = recoveryService.decideNext(eventId, later);
        assertThat(decision.action()).isEqualTo(NextAction.CLOSE_TIMEOUT);
        assertThat(decision.openAttemptId()).isEqualTo(attemptId);

        AdRecoveryService.StaleClose closed = recoveryService.closeStaleAttempt(attemptId, later);
        assertThat(closed.result()).isEqualTo(CloseResult.RETRY_PENDING);
        assertThat(closed.failure().stage()).isEqualTo(FailedStage.AGENT_CALL);
        assertThat(closed.failure().message()).startsWith("5분 초과");
        assertThat(recoveryService.decideNext(eventId, later).action()).isEqualTo(NextAction.GENERATE);

        // 닫힌 뒤에 도착한 승인 응답은 버림
        assertThat(advertisementService.completeGenerate(attemptId, approved(attemptId))).isEmpty();
    }

    @Test
    void 오래_열린_발행_시도를_닫으면_이벤트가_실패한다() {
        AgentGenerateRequestDto request = advertisementService.startGenerate(eventId, AttemptTrigger.AUTO);
        advertisementService.completeGenerate(request.attemptId(), approved(request.attemptId()));
        Long publishAttemptId = advertisementService.startPublish(eventId, AttemptTrigger.AUTO).attemptId();
        em.flush();

        AdRecoveryService.StaleClose closed =
                recoveryService.closeStaleAttempt(publishAttemptId, LocalDateTime.now().plusMinutes(6));

        assertThat(closed.result()).isEqualTo(CloseResult.EVENT_FAILED);
        assertThat(closed.failure().stage()).isEqualTo(FailedStage.PUBLISH);
        assertThat(eventStatus()).isEqualTo(AdEventStatus.FAILED);
        assertThat(recoveryService.findPendingEventIds()).isEmpty();
    }

    @Test
    void 승인_후_발행이_시작되지_않은_이벤트는_5분_뒤에_발행_대상이다() {
        AgentGenerateRequestDto request = advertisementService.startGenerate(eventId, AttemptTrigger.AUTO);
        advertisementService.completeGenerate(request.attemptId(), approved(request.attemptId()));
        em.flush();

        assertThat(recoveryService.decideNext(eventId, LocalDateTime.now()).action()).isEqualTo(NextAction.WAIT);
        assertThat(recoveryService.decideNext(eventId, LocalDateTime.now().plusMinutes(6)).action())
                .isEqualTo(NextAction.PUBLISH);
    }

    @Test
    void 소진됐는데_PENDING으로_남은_이벤트만_실패로_바꾼다() {
        assertThat(recoveryService.failExhaustedEvent(eventId)).isEmpty();

        // 닫을 때의 판정을 거치지 않고 실패 시도 4건을 직접 만듦
        AdEvent event = em.find(AdEvent.class, eventId);
        for (int i = 0; i < 4; i++) {
            AdAttempt attempt = AdAttempt.start(event, AttemptType.GENERATE, AttemptTrigger.RECOVERY);
            attempt.fail(FailedStage.AGENT_CALL, "연결 실패");
            em.persist(attempt);
        }
        em.flush();

        assertThat(recoveryService.decideNext(eventId, LocalDateTime.now()).action()).isEqualTo(NextAction.FAIL_EVENT);
        assertThat(recoveryService.failExhaustedEvent(eventId)).isPresent()
                .get().extracting(AdRecoveryService.Failure::stage).isEqualTo(FailedStage.AGENT_CALL);
        assertThat(eventStatus()).isEqualTo(AdEventStatus.FAILED);
    }
}
