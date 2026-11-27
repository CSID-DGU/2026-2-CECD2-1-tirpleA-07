package com.capstone.triplea.advertisement.dto;

import com.capstone.triplea.advertisement.AdEventStatus;
import com.capstone.triplea.advertisement.AdEventType;
import com.capstone.triplea.advertisement.value.ProductSnapshot;

import java.time.LocalDateTime;
import java.util.List;

// 광고 이벤트 상세
public record AdEventDetailDto(
        Long eventId,
        Long productId,
        AdEventType eventType,
        AdEventStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        ProductSnapshot productSnapshot,    // 이벤트 시점의 상품 정보
        List<AdAttemptDto> attempts,        // 시작 순서 (오래된 것부터)
        AdPostDto post,                     // 게시물 (없으면 null)
        boolean retryable,                  // 지금 수동 재시도를 할 수 있는지
        LocalDateTime retryAvailableAt      // FAILED인데 발행 실패 후 대기 중일 때 가능해지는 시각 (그 외 null)
) {
}
