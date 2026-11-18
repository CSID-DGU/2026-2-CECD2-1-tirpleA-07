package com.capstone.triplea.advertisement.dto;

import com.capstone.triplea.advertisement.AdAttempt;
import com.capstone.triplea.advertisement.AttemptResult;
import com.capstone.triplea.advertisement.AttemptTrigger;
import com.capstone.triplea.advertisement.AttemptType;
import com.capstone.triplea.advertisement.FailedStage;

import java.time.LocalDateTime;

// 처리 시도 1건 (이벤트 상세). result가 null이면 진행 중
public record AdAttemptDto(
        Long attemptId,
        AttemptType attemptType,
        AttemptTrigger triggerType,
        AttemptResult result,
        FailedStage failedStage,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        AdGenerationDto generation      // 생성 결과가 저장된 GENERATE 시도만 (그 외 null)
) {
    public static AdAttemptDto from(AdAttempt attempt, AdGenerationDto generation) {
        return new AdAttemptDto(
                attempt.getId(),
                attempt.getAttemptType(),
                attempt.getTriggerType(),
                attempt.getResult(),
                attempt.getFailedStage(),
                attempt.getErrorMessage(),
                attempt.getStartedAt(),
                attempt.getFinishedAt(),
                generation
        );
    }
}
