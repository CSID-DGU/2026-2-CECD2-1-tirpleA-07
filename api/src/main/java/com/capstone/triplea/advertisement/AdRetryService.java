package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.RetryRejectedException.Code;
import com.capstone.triplea.advertisement.recovery.AdRecoveryProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// 수동 재시도 (FAILED -> PENDING). 재시도 가능 여부 판단은 조회와 재시도가 같이 씀
@Service
@RequiredArgsConstructor
public class AdRetryService {

    private final AdAttemptRepository adAttemptRepository;
    private final AdRecoveryProperties properties;

    // 재시도 가능 여부
    // rejectedCode가 null이면 가능. nextType은 다시 시작할 단계, availableAt은 PUBLISH_WAIT일 때 가능해지는 시각
    public record RetryCheck(Code rejectedCode, AttemptType nextType, LocalDateTime availableAt) {
        public boolean allowed() {
            return rejectedCode == null;
        }
    }

    // FAILED 이벤트만 재시도할 수 있음
    // 승인된 생성 결과가 있으면 발행부터, 없으면 생성부터 다시 함
    // 발행부터 다시 하는 경우 마지막 PUBLISH 실패 후 대기 시간이 지나야 함
    // (그 전에는 방금 게시된 글이 조회되지 않아 게시 안 됨으로 판단하고 중복 게시할 수 있음)
    @Transactional(readOnly = true)
    public RetryCheck check(AdEvent event, LocalDateTime now) {
        if (event.getStatus() != AdEventStatus.FAILED) {
            return new RetryCheck(Code.NOT_FAILED, null, null);
        }
        boolean approved = adAttemptRepository.existsByEventIdAndAttemptTypeAndResult(
                event.getId(), AttemptType.GENERATE, AttemptResult.SUCCESS);
        if (!approved) {
            return new RetryCheck(null, AttemptType.GENERATE, null);
        }
        LocalDateTime availableAt = adAttemptRepository
                .findFirstByEventIdAndAttemptTypeOrderByStartedAtDescIdDesc(event.getId(), AttemptType.PUBLISH)
                .filter(latest -> latest.getResult() == AttemptResult.FAILED && latest.getFinishedAt() != null)
                .map(latest -> latest.getFinishedAt().plus(properties.publishCheckDelay()))
                .orElse(null);
        if (availableAt != null && availableAt.isAfter(now)) {
            return new RetryCheck(Code.PUBLISH_WAIT, AttemptType.PUBLISH, availableAt);
        }
        return new RetryCheck(null, AttemptType.PUBLISH, null);
    }
}
