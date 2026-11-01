package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.value.AdContent;
import com.capstone.triplea.advertisement.value.ProductSnapshot;
import com.capstone.triplea.product.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class AdPostRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private AdPostRepository adPostRepository;

    private AdEvent event;
    private final AdContent content = new AdContent("제목", "본문", "지금 예약", List.of("#다낭"), null);

    @BeforeEach
    void setUp() {
        Product product = em.persist(
                Product.builder().name("다낭 4박 5일").listPrice(1000).price(1000).quantity(10).build());
        event = em.persist(AdEvent.pending(product, AdEventType.NEW,
                ProductSnapshot.ofNew(ProductSnapshot.ProductInfo.from(product))));
    }

    @Test
    void 게시물을_저장하고_이벤트로_조회한다() {
        adPostRepository.save(AdPost.of(event, content, "https://www.facebook.com/1/posts/2"));
        em.flush();
        em.clear();

        AdPost found = adPostRepository.findByEventId(event.getId()).orElseThrow();
        assertThat(found.getContent()).isEqualTo(content);
        assertThat(found.getAdUrl()).isEqualTo("https://www.facebook.com/1/posts/2");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void 이벤트_하나에_게시물은_하나만_저장된다() {
        adPostRepository.saveAndFlush(AdPost.of(event, content, "https://www.facebook.com/1/posts/2"));

        assertThatThrownBy(() -> adPostRepository.saveAndFlush(
                AdPost.of(event, content, "https://www.facebook.com/1/posts/3")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
