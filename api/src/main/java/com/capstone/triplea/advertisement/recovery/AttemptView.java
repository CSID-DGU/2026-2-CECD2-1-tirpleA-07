package com.capstone.triplea.advertisement.recovery;

import com.capstone.triplea.advertisement.AdAttempt;
import com.capstone.triplea.advertisement.AttemptResult;
import com.capstone.triplea.advertisement.AttemptTrigger;
import com.capstone.triplea.advertisement.AttemptType;

import java.time.LocalDateTime;

// 다음 할 일 판단에 필요한 시도 정보 (result가 null이면 열린 시도)
public record AttemptView(
        Long id,
        AttemptType type,
        AttemptTrigger trigger,
        AttemptResult result,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {

    public static AttemptView from(AdAttempt attempt) {
        return new AttemptView(
                attempt.getId(),
                attempt.getAttemptType(),
                attempt.getTriggerType(),
                attempt.getResult(),
                attempt.getStartedAt(),
                attempt.getFinishedAt()
        );
    }

    public boolean isOpen() {
        return result == null;
    }
}
