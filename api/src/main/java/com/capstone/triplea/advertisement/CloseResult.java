package com.capstone.triplea.advertisement;

// 실패한 시도를 닫은 결과
public enum CloseResult {
    DISCARDED,      // 이미 닫힌 시도라 저장하지 않음
    RETRY_PENDING,  // 자동 재시도가 남아 이벤트는 PENDING 유지
    EVENT_FAILED    // 자동 재시도를 모두 써서 이벤트를 FAILED로 바꿈 (알림 대상)
}
