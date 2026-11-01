package com.capstone.triplea.advertisement;

// 처리 시도를 시작한 주체
public enum AttemptTrigger {
    AUTO,       // 상품 등록·수정 이벤트
    RECOVERY,   // 복구 스케줄러
    MANUAL      // 운영 콘솔 수동 재시도
}
