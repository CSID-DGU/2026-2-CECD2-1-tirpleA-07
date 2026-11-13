package com.capstone.triplea.advertisement.recovery;

// PENDING 이벤트의 다음 할 일
public enum NextAction {
    WAIT,           // 아직 할 일 없음
    GENERATE,       // 생성 시도 시작
    PUBLISH,        // 발행 시도 시작
    CLOSE_TIMEOUT,  // 오래 열려 있는 시도를 FAILED로 닫음
    FAIL_EVENT      // 자동 재시도를 모두 써서 이벤트를 FAILED로 바꿈
}
