package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.value.AdContent;
import com.capstone.triplea.advertisement.value.ProductSnapshot;
import com.capstone.triplea.product.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class AdGenerationRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private AdGenerationRepository adGenerationRepository;

    @Autowired
    private AdGenerationIssueRepository adGenerationIssueRepository;

    private AdEvent event;

    @BeforeEach
    void setUp() {
        Product product = em.persist(
                Product.builder().name("다낭 4박 5일").listPrice(1000).price(1000).quantity(10).build());
        event = em.persist(AdEvent.pending(product, AdEventType.NEW,
                ProductSnapshot.ofNew(ProductSnapshot.ProductInfo.from(product))));
    }

    @Test
    void 생성_결과와_위반_사항을_저장하고_다시_읽는다() throws Exception {
        AdAttempt attempt = AdAttempt.start(event, AttemptType.GENERATE, AttemptTrigger.AUTO);
        attempt.reject();
        em.persist(attempt);

        AdContent draft = new AdContent("인생 최고의 여행", "본문", "지금 예약", List.of("#다낭"), null);
        JsonNode votes = new ObjectMapper().readTree(
                "[{\"isAccepted\":true,\"reason\":\"문제 없음\"},{\"isAccepted\":false,\"reason\":\"과장 표현\"}]");
        AdGeneration generation = adGenerationRepository.save(
                AdGeneration.of(attempt, draft, null, "과장 표현이 있어 거절", 1, votes));
        adGenerationIssueRepository.save(AdGenerationIssue.of(generation, "title", "인생 최고의", "[과장 표현] 일상적이지 않은 표현"));
        adGenerationIssueRepository.save(AdGenerationIssue.of(generation, "image", null, "[이미지] 상품 형태 왜곡"));
        em.flush();
        em.clear();

        AdGeneration found = adGenerationRepository.findById(attempt.getId()).orElseThrow();
        assertThat(found.getDraft()).isEqualTo(draft);
        assertThat(found.getContent()).isNull();
        assertThat(found.getReviewReason()).isEqualTo("과장 표현이 있어 거절");
        assertThat(found.getVotes().isArray()).isTrue();
        assertThat(found.getVotes().get(1).get("reason").asText()).isEqualTo("과장 표현");

        assertThat(adGenerationIssueRepository.findByGenerationAttemptIdOrderByIdAsc(attempt.getId()))
                .extracting(AdGenerationIssue::getField)
                .containsExactly("title", "image");
    }

    @Test
    void PUBLISH_시도에는_생성_결과를_만들_수_없다() {
        AdAttempt publish = AdAttempt.start(event, AttemptType.PUBLISH, AttemptTrigger.AUTO);

        assertThatThrownBy(() -> AdGeneration.of(publish, null, null, "사유", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
