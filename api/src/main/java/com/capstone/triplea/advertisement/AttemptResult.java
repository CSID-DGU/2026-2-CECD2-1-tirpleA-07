package com.capstone.triplea.advertisement;

// 처리 시도 결과 (진행 중이면 null)
public enum AttemptResult {
    SUCCESS,    // GENERATE: 심의 승인, PUBLISH: 게시 완료
    REJECTED,   // GENERATE: 심의 거절
    FAILED,     // 실패 (PUBLISH는 확실히 게시되지 않은 경우만)
    UNKNOWN     // PUBLISH: 게시 여부를 알 수 없음
}
