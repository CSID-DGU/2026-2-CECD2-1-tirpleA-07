package com.capstone.triplea.global.exception;

import com.capstone.triplea.advertisement.AdEventNotFoundException;
import com.capstone.triplea.advertisement.RetryRejectedException;
import com.capstone.triplea.advertisement.dto.AdErrorDto;
import com.capstone.triplea.product.exception.ProductNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestControllerAdvice   // 모든 컨트롤러의 예외를 한 곳에서 처리
public class GlobalExceptionHandler {

    // 404: 존재하지 않는 상품
    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<String> handleProductNotFound(ProductNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    // 400: @Valid 유효성 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidation(MethodArgumentNotValidException e) {
        // 필드 에러
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(message);
    }

    // 404: 존재하지 않는 광고 이벤트
    @ExceptionHandler(AdEventNotFoundException.class)
    public ResponseEntity<AdErrorDto> handleAdEventNotFound(AdEventNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new AdErrorDto("EVENT_NOT_FOUND", e.getMessage(), null));
    }

    // 409: 수동 재시도를 받아들일 수 없음 (code로 사유 구분)
    @ExceptionHandler(RetryRejectedException.class)
    public ResponseEntity<AdErrorDto> handleRetryRejected(RetryRejectedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AdErrorDto(e.getCode().name(), e.getMessage(), e.getRetryAvailableAt()));
    }
}
