package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.RetryRejectedException.Code;
import com.capstone.triplea.advertisement.recovery.AdRecoveryProperties;
import com.capstone.triplea.advertisement.dto.AgentGenerateRequestDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// 수동 재시도 (FAILED -> PENDING). 재시도 가능 여부 판단은 조회와 재시도가 같이 씀
@Slf4j
@Service
@RequiredArgsConstructor
public class AdRetryService {

    private final AdEventRepository adEventRepository;
    private final AdAttemptRepository adAttemptRepository;
    private final AdvertisementService advertisementService;
    private final AdRecoveryProperties properties;

    // 재시도 가능 여부
    // rejectedCode가 null이면 가능. nextType은 다시 시작할 단계, availableAt은 PUBLISH_WAIT일 때 가능해지는 시각
    public record RetryCheck(Code rejectedCode, AttemptType nextType, LocalDateTime availableAt) {
        public boolean allowed() {
            return rejectedCode == null;
        }
    }

    // 수동 재시도로 시작한 시도. 실행(agent 호출 또는 게시 여부 확인·발행)은 AdRetryRunner가 이어서 함
    public sealed interface RetryStarted {
        Long eventId();

        Long attemptId();

        AttemptType attemptType();

        record Generate(AgentGenerateRequestDto request) implements RetryStarted {
            public Long eventId() {
                return request.eventId();
            }

            public Long attemptId() {
                return request.attemptId();
            }

            public AttemptType attemptType() {
                return AttemptType.GENERATE;
            }
        }

        record Publish(Long eventId, AdvertisementService.PublishTarget target) implements RetryStarted {
            public Long attemptId() {
                return target.attemptId();
            }

            public AttemptType attemptType() {
                return AttemptType.PUBLISH;
            }
        }
    }

    // 이벤트를 PENDING으로 되돌리고 MANUAL 시도를 시작 (한 트랜잭션)
    // 이벤트만 먼저 PENDING으로 커밋하면, 시도가 생기기 전에 복구 스케줄러가
    // 재시도를 모두 쓴 이벤트로 보고 다시 FAILED로 바꿀 수 있음
    // 동시에 호출되면 이벤트 version 충돌로 한쪽만 성공
    @Transactional
    public RetryStarted start(Long eventId) {
        AdEvent event = adEventRepository.findById(eventId)
                .orElseThrow(() -> new AdEventNotFoundException(eventId));
        RetryCheck check = check(event, LocalDateTime.now());
        if (!check.allowed()) {
            throw new RetryRejectedException(check.rejectedCode(), describe(check, event), check.availableAt());
        }
        event.retry();
        try {
            RetryStarted started = check.nextType() == AttemptType.GENERATE
                    ? new RetryStarted.Generate(advertisementService.startGenerate(eventId, AttemptTrigger.MANUAL))
                    : new RetryStarted.Publish(eventId, advertisementService.startPublish(eventId, AttemptTrigger.MANUAL));
            log.info("[수동 재시도 시작] eventId={}, attemptId={}, attemptType={}",
                    eventId, started.attemptId(), started.attemptType());
            return started;
        } catch (AttemptStartRejectedException | OptimisticLockingFailureException e) {
            throw new RetryRejectedException(Code.CONFLICT, "다른 요청이 먼저 재시도를 시작함: eventId=" + eventId, e);
        }
    }

    private String describe(RetryCheck check, AdEvent event) {
        if (check.rejectedCode() == Code.PUBLISH_WAIT) {
            return "발행 실패 후 " + properties.publishCheckDelay().toMinutes() + "분이 지나야 재시도할 수 있음: eventId="
                    + event.getId();
        }
        return "FAILED 이벤트만 재시도할 수 있음: eventId=" + event.getId() + ", status=" + event.getStatus();
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
