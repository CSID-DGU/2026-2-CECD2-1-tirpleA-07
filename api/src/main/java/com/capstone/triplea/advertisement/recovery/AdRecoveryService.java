package com.capstone.triplea.advertisement.recovery;

import com.capstone.triplea.advertisement.AdAttempt;
import com.capstone.triplea.advertisement.AdAttemptRepository;
import com.capstone.triplea.advertisement.AdEvent;
import com.capstone.triplea.advertisement.AdEventRepository;
import com.capstone.triplea.advertisement.AdEventStatus;
import com.capstone.triplea.advertisement.AdvertisementService;
import com.capstone.triplea.advertisement.AttemptResult;
import com.capstone.triplea.advertisement.AttemptType;
import com.capstone.triplea.advertisement.CloseResult;
import com.capstone.triplea.advertisement.FailedStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// 복구 스케줄러가 쓰는 조회와 상태 변경 (각각 짧은 트랜잭션)
@Slf4j
@Service
@RequiredArgsConstructor
public class AdRecoveryService {

    static final String GENERATE_TIMEOUT_MESSAGE = "5분 초과: 결과가 기록되지 않음 (서버 재시작 또는 응답 유실)";
    static final String PUBLISH_TIMEOUT_MESSAGE = "5분 초과: 결과가 기록되지 않음 (게시됐을 수 있음, 게시 여부 확인 필요)";

    private final AdEventRepository adEventRepository;
    private final AdAttemptRepository adAttemptRepository;
    private final AdvertisementService advertisementService;
    private final RecoveryPolicy recoveryPolicy;
    private final AdRecoveryProperties properties;

    // 다음 할 일. CLOSE_TIMEOUT일 때만 닫을 시도 id가 있음
    public record RecoveryDecision(NextAction action, Long openAttemptId) {
        static RecoveryDecision of(NextAction action) {
            return new RecoveryDecision(action, null);
        }
    }

    // 알림에 쓰는 실패 정보
    public record Failure(FailedStage stage, String message) {
    }

    // 오래 열려 있던 시도를 닫은 결과
    public record StaleClose(CloseResult result, Failure failure) {
    }

    // 처리 대상 후보: PENDING 이벤트를 오래 처리되지 않은 순서로
    @Transactional(readOnly = true)
    public List<Long> findPendingEventIds(int limit) {
        return adEventRepository.findIdsByStatusOrderByUpdatedAtAsc(AdEventStatus.PENDING, PageRequest.of(0, limit));
    }

    // 이벤트의 다음 할 일을 시도 기록으로 판단 (PENDING이 아니면 WAIT)
    @Transactional(readOnly = true)
    public RecoveryDecision decideNext(Long eventId, LocalDateTime now) {
        AdEvent event = adEventRepository.findById(eventId).orElse(null);
        if (event == null || event.getStatus() != AdEventStatus.PENDING) {
            return RecoveryDecision.of(NextAction.WAIT);
        }
        List<AttemptView> attempts = findAttempts(eventId);
        NextAction action = recoveryPolicy.decide(event.getUpdatedAt(), attempts, now);
        if (action == NextAction.CLOSE_TIMEOUT) {
            return new RecoveryDecision(action, attempts.get(attempts.size() - 1).id());
        }
        return RecoveryDecision.of(action);
    }

    // 기준 시간을 넘겨 열려 있는 시도를 FAILED로 닫음 (그 사이 닫혔거나 아직 기준 전이면 건드리지 않음)
    @Transactional
    public StaleClose closeStaleAttempt(Long attemptId, LocalDateTime now) {
        AdAttempt attempt = adAttemptRepository.findWithLockById(attemptId)
                .orElseThrow(() -> new IllegalStateException("처리 시도 없음: attemptId=" + attemptId));
        if (!attempt.isOpen() || attempt.getStartedAt().plus(properties.staleAfter()).isAfter(now)) {
            return new StaleClose(CloseResult.DISCARDED, null);
        }
        if (attempt.getAttemptType() == AttemptType.GENERATE) {
            CloseResult result = advertisementService.failGenerate(attemptId, GENERATE_TIMEOUT_MESSAGE);
            return new StaleClose(result, new Failure(FailedStage.AGENT_CALL, GENERATE_TIMEOUT_MESSAGE));
        }
        CloseResult result = advertisementService.failPublish(attemptId, PUBLISH_TIMEOUT_MESSAGE);
        return new StaleClose(result, new Failure(FailedStage.PUBLISH, PUBLISH_TIMEOUT_MESSAGE));
    }

    // 자동 재시도를 모두 썼는데 PENDING으로 남은 이벤트를 FAILED로 바꿈
    // 보통은 실패한 시도를 닫을 때 함께 바뀌므로, 그 직후 중단된 경우를 위한 안전망
    @Transactional
    public Optional<Failure> failExhaustedEvent(Long eventId) {
        AdEvent event = adEventRepository.findById(eventId).orElse(null);
        if (event == null || event.getStatus() != AdEventStatus.PENDING) {
            return Optional.empty();
        }
        AdAttempt latest = adAttemptRepository.findFirstByEventIdOrderByStartedAtDescIdDesc(eventId).orElse(null);
        if (latest == null || latest.getResult() != AttemptResult.FAILED
                || !recoveryPolicy.isExhausted(findAttempts(eventId), latest.getAttemptType())) {
            return Optional.empty();
        }
        event.fail();
        log.warn("[자동 재시도 소진] eventId={}, 이벤트를 FAILED로 변경", eventId);
        return Optional.of(new Failure(latest.getFailedStage(), latest.getErrorMessage()));
    }

    private List<AttemptView> findAttempts(Long eventId) {
        return adAttemptRepository.findByEventIdOrderByStartedAtAscIdAsc(eventId).stream()
                .map(AttemptView::from)
                .toList();
    }
}
