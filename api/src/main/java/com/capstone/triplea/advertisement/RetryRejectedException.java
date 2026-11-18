package com.capstone.triplea.advertisement;

import lombok.Getter;

import java.time.LocalDateTime;

// 수동 재시도를 받아들일 수 없음 (409)
@Getter
public class RetryRejectedException extends RuntimeException {

    public enum Code {
        NOT_FAILED,     // 이벤트가 FAILED가 아님
        PUBLISH_WAIT,   // 발행 실패 후 대기 시간이 지나지 않음
        CONFLICT        // 다른 요청이 먼저 재시도를 시작함
    }

    private final Code code;
    private final LocalDateTime retryAvailableAt;   // PUBLISH_WAIT일 때만

    public RetryRejectedException(Code code, String message, LocalDateTime retryAvailableAt) {
        super(message);
        this.code = code;
        this.retryAvailableAt = retryAvailableAt;
    }

    public RetryRejectedException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryAvailableAt = null;
    }
}
