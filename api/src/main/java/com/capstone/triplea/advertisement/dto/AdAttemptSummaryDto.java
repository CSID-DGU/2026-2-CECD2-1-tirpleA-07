package com.capstone.triplea.advertisement.dto;

import com.capstone.triplea.advertisement.AdAttempt;
import com.capstone.triplea.advertisement.AttemptResult;
import com.capstone.triplea.advertisement.AttemptTrigger;
import com.capstone.triplea.advertisement.AttemptType;
import com.capstone.triplea.advertisement.FailedStage;

import java.time.LocalDateTime;

// 처리 시도 1건의 요약 (result가 null이면 진행 중)
public record AdAttemptSummaryDto(
        Long attemptId,
        AttemptType attemptType,
        AttemptTrigger triggerType,
        AttemptResult result,
        FailedStage failedStage,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
    public static AdAttemptSummaryDto from(AdAttempt attempt) {
        return new AdAttemptSummaryDto(
                attempt.getId(),
                attempt.getAttemptType(),
                attempt.getTriggerType(),
                attempt.getResult(),
                attempt.getFailedStage(),
                attempt.getErrorMessage(),
                attempt.getStartedAt(),
                attempt.getFinishedAt()
        );
    }
}
