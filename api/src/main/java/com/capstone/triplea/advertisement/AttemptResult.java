package com.capstone.triplea.advertisement;

// 처리 시도 결과 (진행 중이면 null)
public enum AttemptResult {
    SUCCESS,    // GENERATE: 심의 승인, PUBLISH: 게시 완료
    REJECTED,   // GENERATE: 심의 거절
    FAILED,     // 실패 (PUBLISH는 게시됐을 수 있으므로 재발행 전 게시 여부 확인)
    UNKNOWN     // 현재 쓰지 않음 (발행 단계 실패는 FAILED로 기록)
}
