package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.dto.AgentGenerateRequestDto;
import com.capstone.triplea.advertisement.dto.AgentGenerateResponseDto;
import com.capstone.triplea.advertisement.value.AdContent;
import com.capstone.triplea.advertisement.value.ProductSnapshot;
import com.capstone.triplea.product.Product;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;

import java.util.List;

// 조회·수동 재시도 테스트가 함께 쓰는 데이터 준비
final class AdTestFixture {

    static final AdContent DRAFT = new AdContent("초안 제목", "100% 효과 본문", "지금 예약", List.of("#다낭"), null);
    static final AdContent CONTENT = new AdContent("제목", "본문", "지금 예약", List.of("#다낭"), null);

    private AdTestFixture() {
    }

    static Product product(EntityManager em, String name) {
        Product product = Product.builder().name(name).listPrice(1000).price(1000).quantity(10).build();
        em.persist(product);
        return product;
    }

    static Long event(EntityManager em, Product product) {
        AdEvent event = AdEvent.pending(product, AdEventType.NEW,
                ProductSnapshot.ofNew(ProductSnapshot.ProductInfo.from(product)));
        em.persist(event);
        return event.getId();
    }

    // 생성 승인까지
    static Long approve(AdvertisementService service, Long eventId, JsonNode votes) {
        AgentGenerateRequestDto request = service.startGenerate(eventId, AttemptTrigger.AUTO);
        service.completeGenerate(request.attemptId(), new AgentGenerateResponseDto(
                request.attemptId(), AgentGenerateResponseDto.Status.APPROVED, null, null, DRAFT, CONTENT,
                new AgentGenerateResponseDto.Review("과장 표현 수정 후 승인", 1, votes,
                        List.of(new AgentGenerateResponseDto.Issue("body", "100% 효과", "효능을 단정하는 표현")))));
        return request.attemptId();
    }

    // 심의 반려
    static Long reject(AdvertisementService service, Long eventId) {
        AgentGenerateRequestDto request = service.startGenerate(eventId, AttemptTrigger.AUTO);
        service.completeGenerate(request.attemptId(), new AgentGenerateResponseDto(
                request.attemptId(), AgentGenerateResponseDto.Status.REJECTED, null, null, DRAFT, null,
                new AgentGenerateResponseDto.Review("허위 표현", 0, null, List.of())));
        return request.attemptId();
    }

    // 생성 실패를 반복해 이벤트를 FAILED로 (최초 1회 + 자동 재시도 3회)
    static void failGenerateUntilEventFailed(AdvertisementService service, Long eventId) {
        for (int i = 0; i < 4; i++) {
            AgentGenerateRequestDto request = service.startGenerate(
                    eventId, i == 0 ? AttemptTrigger.AUTO : AttemptTrigger.RECOVERY);
            service.failGenerate(request.attemptId(), "agent 연결 실패");
        }
    }

    // 발행 실패를 반복해 이벤트를 FAILED로. 마지막 PUBLISH 시도 id를 반환
    static Long failPublishUntilEventFailed(AdvertisementService service, Long eventId) {
        Long attemptId = null;
        for (int i = 0; i < 4; i++) {
            attemptId = service.startPublish(eventId, i == 0 ? AttemptTrigger.AUTO : AttemptTrigger.RECOVERY).attemptId();
            service.failPublish(attemptId, "timeout");
        }
        return attemptId;
    }
}
