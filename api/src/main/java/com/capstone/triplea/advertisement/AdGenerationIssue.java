package com.capstone.triplea.advertisement;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 심의가 찾아낸 위반 사항 (위반 건만 저장)
// agent가 보낸 순서대로 저장하므로 id 오름차순이 표시 순서
@Entity
@Table(
        name = "AD_GENERATION_ISSUE",
        indexes = @Index(name = "IDX_ISSUE_GENERATION", columnList = "generation_id")
)
@SequenceGenerator(
        name = "AD_GENERATION_ISSUE_SEQ_GENERATOR",
        sequenceName = "AD_GENERATION_ISSUE_SEQ",
        initialValue = 1, allocationSize = 1
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdGenerationIssue {

    @Id
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "AD_GENERATION_ISSUE_SEQ_GENERATOR"
    )
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generation_id", nullable = false)
    private AdGeneration generation;    // 생성 결과 (FK)

    @Column(name = "field", length = 20)
    private String field;               // 문제가 있는 콘텐츠 필드 (title, body, cta, hashtags, image)

    @Column(name = "phrase", length = 1000)
    private String phrase;              // 문제 문구 (draft 원문의 부분 문자열, 없으면 null)

    @Lob
    @Column(name = "reason", nullable = false)
    private String reason;              // 위반 사유

    private AdGenerationIssue(AdGeneration generation, String field, String phrase, String reason) {
        this.generation = generation;
        this.field = field;
        this.phrase = phrase;
        this.reason = reason;
    }

    public static AdGenerationIssue of(AdGeneration generation, String field, String phrase, String reason) {
        return new AdGenerationIssue(generation, field, phrase, reason);
    }
}
