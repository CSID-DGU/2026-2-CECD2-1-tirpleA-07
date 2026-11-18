package com.capstone.triplea.advertisement.dto;

import com.capstone.triplea.advertisement.AttemptType;

// 수동 재시도 수락 응답 (시작한 시도)
public record RetryAcceptedDto(
        Long eventId,
        Long attemptId,
        AttemptType attemptType
) {
}
