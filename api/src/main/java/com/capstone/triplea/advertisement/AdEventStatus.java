package com.capstone.triplea.advertisement;

// 광고 생성 요청(AD_EVENT)의 현재 상태
// 세부 진행 단계(생성 중, 발행 중 등)는 최신 시도(AD_ATTEMPT)로 판단
public enum AdEventStatus {
    PENDING,    // 처리 중이거나 다시 처리해야 함
    PUBLISHED,  // 발행 완료 (AD_POST 존재)
    REJECTED,   // 심의 거절 (종료)
    FAILED      // 실패, 확실히 발행되지 않음 (재시도 가능)
}
