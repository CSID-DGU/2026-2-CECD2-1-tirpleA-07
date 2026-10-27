package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.value.ProductSnapshot;
import com.capstone.triplea.product.Product;
import com.capstone.triplea.product.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class AdEventRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AdEventRepository adEventRepository;

    @Test
    void 상품_스냅샷을_JSON으로_저장하고_다시_읽는다() {
        Product product = productRepository.save(
                Product.builder().name("다낭 4박 5일").listPrice(1000).price(800).quantity(10).build());
        ProductSnapshot.ProductInfo oldInfo =
                new ProductSnapshot.ProductInfo("다낭 4박 5일", null, 1000, 900, 10, null, null);
        ProductSnapshot snapshot = ProductSnapshot.ofDiscount(ProductSnapshot.ProductInfo.from(product), oldInfo);

        AdEvent saved = adEventRepository.save(AdEvent.pending(product, AdEventType.DISCOUNT, snapshot));
        em.flush();
        em.clear();

        AdEvent found = adEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(AdEventStatus.PENDING);
        assertThat(found.getProductSnapshot()).isEqualTo(snapshot);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getVersion()).isZero();
        assertThat(adEventRepository.existsByProductId(product.getId())).isTrue();
    }

    @Test
    void 선점하면_version이_올라간다() {
        Product product = productRepository.save(
                Product.builder().name("두바이 3박 4일").listPrice(1000).price(1000).quantity(5).build());
        AdEvent event = adEventRepository.save(AdEvent.pending(product, AdEventType.NEW,
                ProductSnapshot.ofNew(ProductSnapshot.ProductInfo.from(product))));
        em.flush();

        event.touch();
        em.flush();

        assertThat(event.getVersion()).isEqualTo(1L);
    }
}
