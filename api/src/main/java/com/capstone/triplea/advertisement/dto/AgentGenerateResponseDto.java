package com.capstone.triplea.advertisement.dto;

import com.capstone.triplea.advertisement.FailedStage;
import com.capstone.triplea.advertisement.value.AdContent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

// agent /generate 응답 (처리 결과와 무관하게 항상 이 형태로 옴)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentGenerateResponseDto(
        Long attemptId,
        Status status,
        FailedStage failedStage,    // FAILED일 때만 (MARKETING, REVIEW)
        String errorMessage,
        AdContent draft,            // 심의 전 초안 (APPROVED, REJECTED)
        AdContent content,          // 승인 콘텐츠 (APPROVED)
        Review review               // 심의 결과 (APPROVED, REJECTED)
) {

    public enum Status {
        APPROVED,
        REJECTED,
        FAILED
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Review(
            String reason,          // 채택된 투표의 사유
            Integer selectedVote,   // 채택된 투표 인덱스
            JsonNode votes,         // 투표별 판정·사유 (해석하지 않고 그대로 저장)
            List<Issue> issues
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Issue(
            String field,
            String phrase,
            String reason
    ) {
    }
}
