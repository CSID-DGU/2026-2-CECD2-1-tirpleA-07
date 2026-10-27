package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.value.ProductSnapshot;
import com.capstone.triplea.product.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class AdAttemptRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private AdAttemptRepository adAttemptRepository;

    private AdEvent event;

    @BeforeEach
    void setUp() {
        Product product = em.persist(
                Product.builder().name("다낭 4박 5일").listPrice(1000).price(1000).quantity(10).build());
        event = em.persist(AdEvent.pending(product, AdEventType.NEW,
                ProductSnapshot.ofNew(ProductSnapshot.ProductInfo.from(product))));
    }

    @Test
    void 최신_시도와_열린_시도를_조회한다() {
        AdAttempt generate = AdAttempt.start(event, AttemptType.GENERATE, AttemptTrigger.AUTO);
        generate.succeed();
        adAttemptRepository.save(generate);
        AdAttempt publish = adAttemptRepository.save(AdAttempt.start(event, AttemptType.PUBLISH, AttemptTrigger.AUTO));
        em.flush();
        em.clear();

        assertThat(adAttemptRepository.findFirstByEventIdOrderByStartedAtDescIdDesc(event.getId()))
                .get().extracting(AdAttempt::getId).isEqualTo(publish.getId());
        assertThat(adAttemptRepository.findFirstByEventIdAndAttemptTypeAndResultOrderByStartedAtDescIdDesc(
                event.getId(), AttemptType.GENERATE, AttemptResult.SUCCESS))
                .get().extracting(AdAttempt::getId).isEqualTo(generate.getId());
        assertThat(adAttemptRepository.existsByEventIdAndResultIsNull(event.getId())).isTrue();
        assertThat(adAttemptRepository.findByEventIdOrderByStartedAtAscIdAsc(event.getId()))
                .extracting(AdAttempt::getAttemptType)
                .containsExactly(AttemptType.GENERATE, AttemptType.PUBLISH);
    }
}
