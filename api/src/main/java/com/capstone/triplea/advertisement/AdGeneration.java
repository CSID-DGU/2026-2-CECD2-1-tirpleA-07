package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.value.AdContent;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// agent 응답 (생성 + 심의가 모두 끝난 GENERATE 시도만 존재)
// api는 응답 값을 해석하지 않고 그대로 저장
@Entity
@Table(name = "AD_GENERATION")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdGeneration {

    @Id
    @Column(name = "attempt_id")
    private Long attemptId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attempt_id")
    private AdAttempt attempt;          // GENERATE 시도 (PK 공유)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft", nullable = false)
    private AdContent draft;            // 심의 전 마케팅 초안

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content")
    private AdContent content;          // 승인 콘텐츠 (거절이면 null)

    @Lob
    @Column(name = "review_reason", nullable = false)
    private String reviewReason;        // 심의 판정 사유 (채택된 투표의 사유)

    @Column(name = "selected_vote")
    private Integer selectedVote;       // 채택된 투표의 votes 인덱스

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "votes")
    private JsonNode votes;             // 투표별 판정·사유 [{isAccepted, reason}] (해석하지 않음)

    private AdGeneration(AdAttempt attempt, AdContent draft, AdContent content,
                         String reviewReason, Integer selectedVote, JsonNode votes) {
        this.attempt = attempt;
        this.draft = draft;
        this.content = content;
        this.reviewReason = reviewReason;
        this.selectedVote = selectedVote;
        this.votes = votes;
    }

    public static AdGeneration of(AdAttempt attempt, AdContent draft, AdContent content,
                                  String reviewReason, Integer selectedVote, JsonNode votes) {
        if (attempt.getAttemptType() != AttemptType.GENERATE) {
            throw new IllegalArgumentException("GENERATE 시도가 아님: attemptId=" + attempt.getId());
        }
        return new AdGeneration(attempt, draft, content, reviewReason, selectedVote, votes);
    }
}
