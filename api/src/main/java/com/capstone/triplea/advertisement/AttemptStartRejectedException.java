package com.capstone.triplea.advertisement;

// 시도를 시작할 수 없음: 다른 쪽이 이미 처리했거나 처리 중이라는 뜻이며 오류가 아님
// (이벤트가 PENDING이 아님, 진행 중인 시도 있음, 승인된 생성이 이미 있음, 선점 충돌)
public class AttemptStartRejectedException extends RuntimeException {

    public AttemptStartRejectedException(String message) {
        super(message);
    }

    public AttemptStartRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
