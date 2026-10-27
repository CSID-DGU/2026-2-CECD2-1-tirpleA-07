package com.capstone.triplea.advertisement;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 처리 시도 1회 (agent 호출 또는 Facebook 발행)
// result가 null이면 진행 중인 시도
@Entity
@Table(
        name = "AD_ATTEMPT",
        indexes = @Index(name = "IDX_ATTEMPT_EVENT", columnList = "event_id, started_at")
)
@SequenceGenerator(
        name = "AD_ATTEMPT_SEQ_GENERATOR",
        sequenceName = "AD_ATTEMPT_SEQ",
        initialValue = 1, allocationSize = 1
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdAttempt {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "AD_ATTEMPT_SEQ_GENERATOR"
    )
    @Column(name = "id")
    private Long id;                        // agent 요청의 attemptId

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private AdEvent event;                  // 이벤트 id (FK)

    @Enumerated(EnumType.STRING)
    @Column(name = "attempt_type", nullable = false, length = 20)
    private AttemptType attemptType;        // 시도 종류

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private AttemptTrigger triggerType;     // 시도 주체

    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 20)
    private AttemptResult result;           // 시도 결과 (진행 중이면 null)

    @Enumerated(EnumType.STRING)
    @Column(name = "failed_stage", length = 20)
    private FailedStage failedStage;        // 실패 단계 (FAILED일 때만)

    @Column(name = "error_message", length = ERROR_MESSAGE_MAX_LENGTH)
    private String errorMessage;            // 실패·불확실 사유

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;       // 진행 중이면 null

    private AdAttempt(AdEvent event, AttemptType attemptType, AttemptTrigger triggerType) {
        this.event = event;
        this.attemptType = attemptType;
        this.triggerType = triggerType;
    }

    public static AdAttempt start(AdEvent event, AttemptType attemptType, AttemptTrigger triggerType) {
        return new AdAttempt(event, attemptType, triggerType);
    }

    public boolean isOpen() {
        return result == null;
    }

    // GENERATE: 심의 승인, PUBLISH: 게시 완료
    public void succeed() {
        close(AttemptResult.SUCCESS);
    }

    // GENERATE: 심의 거절
    public void reject() {
        requireType(AttemptType.GENERATE);
        close(AttemptResult.REJECTED);
    }

    // 실패 (PUBLISH는 확실히 게시되지 않은 경우만)
    public void fail(FailedStage failedStage, String errorMessage) {
        close(AttemptResult.FAILED);
        this.failedStage = failedStage;
        this.errorMessage = truncate(errorMessage);
    }

    // PUBLISH: 게시 여부를 알 수 없음 (타임아웃, 5xx 등)
    public void unknown(String errorMessage) {
        requireType(AttemptType.PUBLISH);
        close(AttemptResult.UNKNOWN);
        this.errorMessage = truncate(errorMessage);
    }

    private void close(AttemptResult result) {
        if (!isOpen()) {
            throw new IllegalStateException("이미 종료된 시도: id=" + id + ", result=" + this.result);
        }
        this.result = result;
        this.finishedAt = LocalDateTime.now();
    }

    private void requireType(AttemptType expected) {
        if (this.attemptType != expected) {
            throw new IllegalStateException(
                    "시도 종류가 " + expected + "가 아님: id=" + id + ", attemptType=" + attemptType);
        }
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    @PrePersist
    public void prePersist() {
        this.startedAt = LocalDateTime.now();
    }
}
