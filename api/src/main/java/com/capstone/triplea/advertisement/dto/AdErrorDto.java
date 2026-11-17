package com.capstone.triplea.advertisement.dto;

import java.time.LocalDateTime;

// 광고 이벤트 API의 오류 응답 (화면이 code로 종류를 구분)
public record AdErrorDto(
        String code,
        String message,
        LocalDateTime retryAvailableAt     // PUBLISH_WAIT일 때만
) {
}
