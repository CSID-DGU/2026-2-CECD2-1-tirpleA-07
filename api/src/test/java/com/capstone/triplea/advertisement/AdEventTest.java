package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.value.ProductSnapshot;
import com.capstone.triplea.product.Product;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdEventTest {

    private AdEvent newEvent() {
        Product product = Product.builder().name("다낭 4박 5일").listPrice(1000).price(900).quantity(10).build();
        return AdEvent.pending(product, AdEventType.NEW, ProductSnapshot.ofNew(ProductSnapshot.ProductInfo.from(product)));
    }

    @Test
    void 생성하면_PENDING이다() {
        assertThat(newEvent().getStatus()).isEqualTo(AdEventStatus.PENDING);
    }

    @Test
    void PENDING에서_종료_상태로_전이한다() {
        AdEvent published = newEvent();
        published.publish();
        assertThat(published.getStatus()).isEqualTo(AdEventStatus.PUBLISHED);

        AdEvent rejected = newEvent();
        rejected.reject();
        assertThat(rejected.getStatus()).isEqualTo(AdEventStatus.REJECTED);
    }

    @Test
    void FAILED는_재시도하면_PENDING으로_돌아간다() {
        AdEvent event = newEvent();
        event.fail();
        event.retry();
        assertThat(event.getStatus()).isEqualTo(AdEventStatus.PENDING);
    }

    @Test
    void 종료_상태에서는_전이할_수_없다() {
        AdEvent event = newEvent();
        event.publish();

        assertThatThrownBy(event::fail).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(event::retry).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(event::touch).isInstanceOf(IllegalStateException.class);
    }
}
