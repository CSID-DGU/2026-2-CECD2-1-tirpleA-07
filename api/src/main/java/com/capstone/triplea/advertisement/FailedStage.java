package com.capstone.triplea.advertisement;

// 처리 시도가 실패한 단계
public enum FailedStage {
    AGENT_CALL, // agent 연결 실패, 타임아웃, 비정상 응답
    MARKETING,  // agent 마케팅 문구 생성 실패
    REVIEW,     // agent 심의 실패
    PUBLISH     // Facebook 발행 실패
}
