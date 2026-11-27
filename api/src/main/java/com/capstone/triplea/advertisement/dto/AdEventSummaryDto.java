package com.capstone.triplea.advertisement.dto;

import com.capstone.triplea.advertisement.AdEventStatus;
import com.capstone.triplea.advertisement.AdEventType;

import java.time.LocalDateTime;

// 광고 이벤트 목록의 항목
public record AdEventSummaryDto(
        Long eventId,
        Long productId,
        String productName,                 // 이벤트 시점의 상품명
        AdEventType eventType,
        AdEventStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int attemptCount,
        AdAttemptSummaryDto latestAttempt,  // 가장 최근에 시작한 시도 (없으면 null)
        String postUrl                      // 게시물 URL (PUBLISHED일 때만)
) {
}
