package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.dto.AdAttemptDto;
import com.capstone.triplea.advertisement.dto.AdEventDetailDto;
import com.capstone.triplea.advertisement.dto.AdEventSortType;
import com.capstone.triplea.advertisement.dto.AdEventSummaryDto;
import com.capstone.triplea.advertisement.dto.AdGenerationDto;
import com.capstone.triplea.advertisement.dto.AgentGenerateRequestDto;
import com.capstone.triplea.advertisement.recovery.AdRecoveryProperties;
import com.capstone.triplea.advertisement.recovery.RecoveryPolicy;
import com.capstone.triplea.product.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 광고 이벤트 목록·상세 조회
@DataJpaTest
@Import({AdvertisementService.class, AdEventQueryService.class, AdRetryService.class, RecoveryPolicy.class,
        AdEventQueryServiceTest.Config.class})
class AdEventQueryServiceTest {

    private static final String POST_URL = "https://www.facebook.com/111/posts/1";

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
    private AdEventQueryService queryService;

    private final ObjectMapper json = new ObjectMapper();

    private Product productA;
    private Product productB;
    private Long pendingId;     // A, 시도 없음
    private Long publishedId;   // A, 발행 완료
    private Long failedId;      // B, 생성 실패
    private Long rejectedId;    // B, 심의 반려

    @BeforeEach
    void setUp() {
        productA = AdTestFixture.product(em.getEntityManager(), "상품 A");
        productB = AdTestFixture.product(em.getEntityManager(), "상품 B");
        pendingId = AdTestFixture.event(em.getEntityManager(), productA);
        publishedId = AdTestFixture.event(em.getEntityManager(), productA);
        failedId = AdTestFixture.event(em.getEntityManager(), productB);
        rejectedId = AdTestFixture.event(em.getEntityManager(), productB);

        AdTestFixture.approve(advertisementService, publishedId, null);
        AdvertisementService.PublishTarget target = advertisementService.startPublish(publishedId, AttemptTrigger.AUTO);
        advertisementService.completePublish(target.attemptId(), target.content(), POST_URL);
        AdTestFixture.failGenerateUntilEventFailed(advertisementService, failedId);
        AdTestFixture.reject(advertisementService, rejectedId);
        em.flush();
        em.clear();
    }

    private List<Long> ids(AdEventStatus status, Long productId, AdEventSortType sort) {
        return queryService.findEvents(status, productId, sort, 0, 20)
                .map(AdEventSummaryDto::eventId).getContent();
    }

    @Test
    void 조건이_없으면_전체를_최신순으로_반환한다() {
        assertThat(ids(null, null, AdEventSortType.CREATED_AT_DESC))
                .containsExactly(rejectedId, failedId, publishedId, pendingId);
        assertThat(ids(null, null, AdEventSortType.CREATED_AT_ASC))
                .containsExactly(pendingId, publishedId, failedId, rejectedId);
    }

    @Test
    void 상태와_상품으로_거른다() {
        assertThat(ids(AdEventStatus.FAILED, null, AdEventSortType.CREATED_AT_DESC)).containsExactly(failedId);
        assertThat(ids(null, productA.getId(), AdEventSortType.CREATED_AT_DESC)).containsExactly(publishedId, pendingId);
        assertThat(ids(AdEventStatus.REJECTED, productB.getId(), AdEventSortType.CREATED_AT_DESC))
                .containsExactly(rejectedId);
        assertThat(ids(AdEventStatus.FAILED, productA.getId(), AdEventSortType.CREATED_AT_DESC)).isEmpty();
        // 없는 상품은 오류가 아니라 빈 목록
        assertThat(ids(null, -1L, AdEventSortType.CREATED_AT_DESC)).isEmpty();
    }

    @Test
    void 페이지_단위로_나누고_크기는_100까지만_허용한다() {
        Page<AdEventSummaryDto> first = queryService.findEvents(null, null, AdEventSortType.CREATED_AT_DESC, 0, 3);
        Page<AdEventSummaryDto> second = queryService.findEvents(null, null, AdEventSortType.CREATED_AT_DESC, 1, 3);

        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).hasSize(3);
        assertThat(second.map(AdEventSummaryDto::eventId).getContent()).containsExactly(pendingId);

        assertThat(queryService.findEvents(null, null, AdEventSortType.CREATED_AT_DESC, 0, 1000).getSize())
                .isEqualTo(100);
        assertThat(queryService.findEvents(null, null, AdEventSortType.CREATED_AT_DESC, -1, 0).getSize())
                .isEqualTo(1);
    }

    @Test
    void 목록의_항목은_최신_시도와_시도_수와_게시물_URL을_담는다() {
        List<AdEventSummaryDto> events =
                queryService.findEvents(null, null, AdEventSortType.CREATED_AT_ASC, 0, 20).getContent();
        AdEventSummaryDto pending = events.get(0);
        AdEventSummaryDto published = events.get(1);
        AdEventSummaryDto failed = events.get(2);

        assertThat(pending.attemptCount()).isZero();
        assertThat(pending.latestAttempt()).isNull();
        assertThat(pending.postUrl()).isNull();
        assertThat(pending.productId()).isEqualTo(productA.getId());
        assertThat(pending.productName()).isEqualTo("상품 A");

        assertThat(published.status()).isEqualTo(AdEventStatus.PUBLISHED);
        assertThat(published.attemptCount()).isEqualTo(2);
        assertThat(published.latestAttempt().attemptType()).isEqualTo(AttemptType.PUBLISH);
        assertThat(published.latestAttempt().result()).isEqualTo(AttemptResult.SUCCESS);
        assertThat(published.postUrl()).isEqualTo(POST_URL);

        assertThat(failed.status()).isEqualTo(AdEventStatus.FAILED);
        assertThat(failed.attemptCount()).isEqualTo(4);
        assertThat(failed.latestAttempt().triggerType()).isEqualTo(AttemptTrigger.RECOVERY);
        assertThat(failed.latestAttempt().result()).isEqualTo(AttemptResult.FAILED);
        assertThat(failed.latestAttempt().failedStage()).isEqualTo(FailedStage.AGENT_CALL);
        assertThat(failed.latestAttempt().errorMessage()).isEqualTo("agent 연결 실패");
        assertThat(failed.postUrl()).isNull();
    }

    @Test
    void 진행_중인_시도는_결과가_없는_최신_시도로_보인다() {
        AgentGenerateRequestDto request = advertisementService.startGenerate(pendingId, AttemptTrigger.AUTO);
        em.flush();
        em.clear();

        AdEventSummaryDto pending = queryService.findEvents(AdEventStatus.PENDING, null, AdEventSortType.CREATED_AT_DESC, 0, 20)
                .getContent().get(0);

        assertThat(pending.latestAttempt().attemptId()).isEqualTo(request.attemptId());
        assertThat(pending.latestAttempt().result()).isNull();
        assertThat(pending.latestAttempt().finishedAt()).isNull();
    }

    @Test
    void 상세는_시도_이력과_생성_결과와_게시물을_담는다() {
        AdEventDetailDto detail = queryService.findEvent(publishedId);

        assertThat(detail.status()).isEqualTo(AdEventStatus.PUBLISHED);
        assertThat(detail.productId()).isEqualTo(productA.getId());
        assertThat(detail.productSnapshot().productNew().name()).isEqualTo("상품 A");
        assertThat(detail.productSnapshot().productOld()).isNull();
        assertThat(detail.attempts()).extracting(AdAttemptDto::attemptType)
                .containsExactly(AttemptType.GENERATE, AttemptType.PUBLISH);

        AdGenerationDto generation = detail.attempts().get(0).generation();
        assertThat(generation.draft()).isEqualTo(AdTestFixture.DRAFT);
        assertThat(generation.content()).isEqualTo(AdTestFixture.CONTENT);
        assertThat(generation.reviewReason()).isEqualTo("과장 표현 수정 후 승인");
        assertThat(generation.selectedVote()).isEqualTo(1);
        assertThat(generation.votes()).isEmpty();
        assertThat(generation.issues()).containsExactly(
                new AdGenerationDto.Issue("body", "100% 효과", "효능을 단정하는 표현"));
        // 발행 시도에는 생성 결과가 없음
        assertThat(detail.attempts().get(1).generation()).isNull();

        assertThat(detail.post().adUrl()).isEqualTo(POST_URL);
        assertThat(detail.post().content()).isEqualTo(AdTestFixture.CONTENT);
        assertThat(detail.post().createdAt()).isNotNull();
        assertThat(detail.retryable()).isFalse();
        assertThat(detail.retryAvailableAt()).isNull();
    }

    @Test
    void 반려된_건은_초안과_사유만_있고_승인_문구가_없다() {
        AdEventDetailDto detail = queryService.findEvent(rejectedId);

        AdGenerationDto generation = detail.attempts().get(0).generation();
        assertThat(detail.attempts().get(0).result()).isEqualTo(AttemptResult.REJECTED);
        assertThat(generation.draft()).isEqualTo(AdTestFixture.DRAFT);
        assertThat(generation.content()).isNull();
        assertThat(generation.reviewReason()).isEqualTo("허위 표현");
        assertThat(generation.issues()).isEmpty();
        assertThat(detail.post()).isNull();
        assertThat(detail.retryable()).isFalse();
    }

    @Test
    void 투표는_판정과_사유만_꺼내_반환한다() throws Exception {
        JsonNode votes = json.readTree(
                "[{\"isAccepted\":false,\"reason\":\"과장\",\"revised\":{\"title\":\"x\"}},{\"isAccepted\":true,\"reason\":\"문제 없음\"}]");
        AdTestFixture.approve(advertisementService, pendingId, votes);
        em.flush();
        em.clear();

        AdGenerationDto generation = queryService.findEvent(pendingId).attempts().get(0).generation();

        assertThat(generation.votes()).containsExactly(
                new AdGenerationDto.Vote(false, "과장"), new AdGenerationDto.Vote(true, "문제 없음"));
    }

    @Test
    void 실패한_이벤트는_재시도_가능_여부를_알린다() {
        // 생성 실패: 바로 가능
        AdEventDetailDto generateFailed = queryService.findEvent(failedId);
        assertThat(generateFailed.retryable()).isTrue();
        assertThat(generateFailed.retryAvailableAt()).isNull();
        assertThat(generateFailed.attempts()).hasSize(4).allSatisfy(attempt -> assertThat(attempt.generation()).isNull());

        // 발행 실패 직후: 대기 중
        AdTestFixture.approve(advertisementService, pendingId, null);
        Long lastAttemptId = AdTestFixture.failPublishUntilEventFailed(advertisementService, pendingId);
        em.flush();
        em.clear();
        AdEventDetailDto publishFailed = queryService.findEvent(pendingId);
        assertThat(publishFailed.status()).isEqualTo(AdEventStatus.FAILED);
        assertThat(publishFailed.retryable()).isFalse();
        assertThat(publishFailed.retryAvailableAt())
                .isEqualTo(em.find(AdAttempt.class, lastAttemptId).getFinishedAt().plusMinutes(5));
    }

    @Test
    void 시도가_없는_이벤트와_없는_이벤트() {
        AdEventDetailDto detail = queryService.findEvent(pendingId);
        assertThat(detail.attempts()).isEmpty();
        assertThat(detail.post()).isNull();
        assertThat(detail.retryable()).isFalse();

        assertThatThrownBy(() -> queryService.findEvent(-1L)).isInstanceOf(AdEventNotFoundException.class);
    }
}
