package com.capstone.triplea.advertisement.dto;

import com.capstone.triplea.advertisement.AdGeneration;
import com.capstone.triplea.advertisement.AdGenerationIssue;
import com.capstone.triplea.advertisement.value.AdContent;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

// 생성 결과와 심의 근거 (GENERATE 시도가 승인 또는 반려로 끝났을 때)
public record AdGenerationDto(
        AdContent draft,        // 심의 전 초안
        AdContent content,      // 승인된 문구 (반려면 null)
        String reviewReason,
        Integer selectedVote,   // 채택된 투표의 votes 인덱스
        List<Vote> votes,
        List<Issue> issues
) {
    public record Vote(
            @JsonProperty("isAccepted") Boolean isAccepted,
            String reason
    ) {
    }

    public record Issue(
            String field,       // title, body, cta, hashtags, image
            String phrase,      // 초안 원문의 부분 문자열 (없으면 null)
            String reason
    ) {
    }

    public static AdGenerationDto from(AdGeneration generation, List<AdGenerationIssue> issues) {
        return new AdGenerationDto(
                generation.getDraft(),
                generation.getContent(),
                generation.getReviewReason(),
                generation.getSelectedVote(),
                toVotes(generation.getVotes()),
                issues.stream()
                        .map(issue -> new Issue(issue.getField(), issue.getPhrase(), issue.getReason()))
                        .toList()
        );
    }

    // votes는 agent가 준 JSON을 그대로 저장한 값이라, 응답에서는 판정과 사유만 꺼내 형식을 고정함
    // 형식이 다른 원소도 빼지 않음 (selectedVote 인덱스가 어긋나지 않게). 배열이 아니면 빈 목록
    static List<Vote> toVotes(JsonNode votes) {
        List<Vote> result = new ArrayList<>();
        if (votes == null || !votes.isArray()) {
            return result;
        }
        for (JsonNode vote : votes) {
            JsonNode accepted = vote.get("isAccepted");
            JsonNode reason = vote.get("reason");
            result.add(new Vote(
                    accepted != null && accepted.isBoolean() ? accepted.booleanValue() : null,
                    reason != null && reason.isTextual() ? reason.textValue() : null));
        }
        return result;
    }
}
