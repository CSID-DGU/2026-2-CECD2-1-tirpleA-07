package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.recovery.AdRecoveryProperties;
import com.capstone.triplea.advertisement.recovery.RecoveryPolicy;
import com.capstone.triplea.product.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 수동 재시도 동시 호출: 각 호출이 자기 트랜잭션으로 커밋하도록 테스트 트랜잭션을 끔
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AdvertisementService.class, AdRetryService.class, RecoveryPolicy.class, AdRetryConcurrencyTest.Config.class})
class AdRetryConcurrencyTest {

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
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager em;
    @Autowired
    private AdvertisementService advertisementService;
    @Autowired
    private AdRetryService retryService;
    @Autowired
    private AdEventRepository eventRepository;
    @Autowired
    private AdAttemptRepository attemptRepository;

    @Test
    void 동시에_두_번_호출하면_한쪽만_시작한다() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long eventId = tx.execute(status -> {
            Product product = AdTestFixture.product(em, "동시 재시도");
            return AdTestFixture.event(em, product);
        });
        AdTestFixture.failGenerateUntilEventFailed(advertisementService, eventId);

        int callers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return retryService.start(eventId);
                } catch (RetryRejectedException e) {
                    return e;
                }
            }));
        }
        ready.await();
        go.countDown();

        int started = 0;
        int rejected = 0;
        for (Future<Object> result : results) {
            Object value = result.get(10, TimeUnit.SECONDS);
            if (value instanceof AdRetryService.RetryStarted) {
                started++;
            } else {
                // 선점 충돌이면 CONFLICT, 먼저 끝난 뒤에 읽었으면 NOT_FAILED
                assertThat(((RetryRejectedException) value).getCode())
                        .isIn(RetryRejectedException.Code.CONFLICT, RetryRejectedException.Code.NOT_FAILED);
                rejected++;
            }
        }
        pool.shutdown();

        assertThat(started).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        List<AdAttempt> attempts = attemptRepository.findByEventIdOrderByStartedAtAscIdAsc(eventId);
        assertThat(attempts.stream().filter(attempt -> attempt.getTriggerType() == AttemptTrigger.MANUAL)).hasSize(1);
        assertThat(attempts.stream().filter(AdAttempt::isOpen)).hasSize(1);
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(AdEventStatus.PENDING);
    }
}
