package com.capstone.triplea.advertisement.recovery;

import com.capstone.triplea.advertisement.AdAttempt;
import com.capstone.triplea.advertisement.AdEvent;
import com.capstone.triplea.advertisement.AdEventStatus;
import com.capstone.triplea.advertisement.AdEventType;
import com.capstone.triplea.advertisement.AdvertisementService;
import com.capstone.triplea.advertisement.AdvertisementService.PublishTarget;
import com.capstone.triplea.advertisement.AttemptTrigger;
import com.capstone.triplea.advertisement.CloseResult;
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

// 발행 자동 재시도를 켠 상태(publish-retry-enabled=true)의 발행 재시도 흐름
@DataJpaTest
@Import({AdvertisementService.class, AdRecoveryService.class, RecoveryPolicy.class,
        AdPublishRetryIntegrationTest.Config.class})
class AdPublishRetryIntegrationTest {

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
    private AdRecoveryService recoveryService;

    private Long eventId;

    @BeforeEach
    void setUp() {
        Product product = em.persist(
                Product.builder().name("다낭 4박 5일").listPrice(1000).price(1000).quantity(10).build());
        AdEvent event = em.persist(AdEvent.pending(product, AdEventType.NEW,
                ProductSnapshot.ofNew(ProductSnapshot.ProductInfo.from(product))));
        eventId = event.getId();

        // 생성 승인까지 끝난 상태
        AgentGenerateRequestDto request = advertisementService.startGenerate(eventId, AttemptTrigger.AUTO);
        AdContent content = new AdContent("제목", "본문", "지금 예약", List.of("#다낭"), null);
        advertisementService.completeGenerate(request.attemptId(), new AgentGenerateResponseDto(
                request.attemptId(), AgentGenerateResponseDto.Status.APPROVED, null, null,
                content, content, new AgentGenerateResponseDto.Review("문제 없음", 0, null, List.of())));
    }

    private AdEventStatus eventStatus() {
        em.flush();
        em.clear();
        return em.find(AdEvent.class, eventId).getStatus();
    }

    private LocalDateTime startedAt(Long attemptId) {
        em.flush();
        return em.find(AdAttempt.class, attemptId).getStartedAt();
    }

    @Test
    void 첫_발행은_게시_여부를_확인하지_않는다() {
        PublishTarget target = advertisementService.startPublish(eventId, AttemptTrigger.AUTO);

        assertThat(target.verifySince()).isNull();
    }

    @Test
    void 이전_발행_시도가_있으면_가장_이른_시작_시각부터_확인한다() {
        PublishTarget first = advertisementService.startPublish(eventId, AttemptTrigger.AUTO);
        LocalDateTime firstStartedAt = startedAt(first.attemptId());
        advertisementService.failPublish(first.attemptId(), "timeout");

        PublishTarget second = advertisementService.startPublish(eventId, AttemptTrigger.RECOVERY);
        assertThat(second.verifySince()).isEqualTo(firstStartedAt);
        advertisementService.failPublish(second.attemptId(), "게시 여부 확인 불가");

        // 직전 시도가 아니라 첫 발행 시도 기준
        PublishTarget third = advertisementService.startPublish(eventId, AttemptTrigger.RECOVERY);
        assertThat(third.verifySince()).isEqualTo(firstStartedAt);
    }

    @Test
    void 발행_실패는_4번째에서만_이벤트를_실패로_바꾼다() {
        for (int i = 1; i <= 3; i++) {
            PublishTarget target = advertisementService.startPublish(
                    eventId, i == 1 ? AttemptTrigger.AUTO : AttemptTrigger.RECOVERY);
            assertThat(advertisementService.failPublish(target.attemptId(), "timeout"))
                    .isEqualTo(CloseResult.RETRY_PENDING);
            assertThat(eventStatus()).isEqualTo(AdEventStatus.PENDING);
        }

        PublishTarget last = advertisementService.startPublish(eventId, AttemptTrigger.RECOVERY);
        assertThat(advertisementService.failPublish(last.attemptId(), "timeout")).isEqualTo(CloseResult.EVENT_FAILED);
        assertThat(eventStatus()).isEqualTo(AdEventStatus.FAILED);
    }

    @Test
    void 발행_실패_5분_뒤에_다시_발행_대상이_된다() {
        PublishTarget target = advertisementService.startPublish(eventId, AttemptTrigger.AUTO);
        advertisementService.failPublish(target.attemptId(), "timeout");
        em.flush();
        LocalDateTime now = LocalDateTime.now();

        assertThat(recoveryService.decideNext(eventId, now.plusMinutes(4)).action()).isEqualTo(NextAction.WAIT);
        assertThat(recoveryService.decideNext(eventId, now.plusMinutes(6)).action()).isEqualTo(NextAction.PUBLISH);
    }

    @Test
    void 피드에서_찾은_게시물로_발행을_확정한다() {
        PublishTarget first = advertisementService.startPublish(eventId, AttemptTrigger.AUTO);
        advertisementService.failPublish(first.attemptId(), "timeout");
        PublishTarget second = advertisementService.startPublish(eventId, AttemptTrigger.RECOVERY);

        boolean saved = advertisementService.completePublish(
                second.attemptId(), second.content(), "https://www.facebook.com/111/posts/2");

        assertThat(saved).isTrue();
        assertThat(eventStatus()).isEqualTo(AdEventStatus.PUBLISHED);
    }
}
