package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.dto.AdvertisementResponseDto;

import com.capstone.triplea.advertisement.dto.AgentGenerateRequestDto;
import com.capstone.triplea.advertisement.dto.AgentGenerateResponseDto;
import com.capstone.triplea.advertisement.value.AdContent;
import com.capstone.triplea.advertisement.value.ProductSnapshot;
import com.capstone.triplea.product.Product;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdvertisementService {
    private final AdEventRepository adEventRepository;
    private final AdAttemptRepository adAttemptRepository;
    private final AdGenerationRepository adGenerationRepository;
    private final AdGenerationIssueRepository adGenerationIssueRepository;
    private final AdPostRepository adPostRepository;
    private final ObjectMapper objectMapper;

    // 발행할 콘텐츠와 PUBLISH 시도 id
    public record PublishTarget(Long attemptId, AdContent content) {
    }

    // 생성 시도 결과 (알림에 필요한 실패 정보와 거절 사유 포함)
    public record GenerateResult(AttemptResult result, FailedStage failedStage, String errorMessage, String reviewReason) {
    }

    // 광고 생성 요청을 PENDING으로 저장 (상품 저장과 같은 트랜잭션에서 호출)
    @Transactional
    public Long createEvent(Product product, AdEventType eventType, ProductSnapshot snapshot) {
        AdEvent event = adEventRepository.save(AdEvent.pending(product, eventType, snapshot));
        log.info("[광고 이벤트 생성] eventId={}, productId={}, eventType={}", event.getId(), product.getId(), eventType);
        return event.getId();
    }

    // GENERATE 시도를 기록하고 agent 요청을 만든다
    @Transactional
    public AgentGenerateRequestDto startGenerate(Long eventId, AttemptTrigger trigger) {
        AdEvent event = occupyEvent(eventId);
        AdAttempt attempt = adAttemptRepository.save(AdAttempt.start(event, AttemptType.GENERATE, trigger));
        ProductSnapshot snapshot = event.getProductSnapshot();
        return new AgentGenerateRequestDto(
                attempt.getId(),
                eventId,
                event.getEventType(),
                snapshot.productNew(),
                snapshot.productOld(),
                false
        );
    }

    // agent 응답을 저장하고 시도 결과를 반환 (SUCCESS면 발행 단계로 진행)
    // 이미 닫힌 시도면 저장하지 않고 빈 값을 반환
    @Transactional
    public Optional<GenerateResult> completeGenerate(Long attemptId, AgentGenerateResponseDto response) {
        AdAttempt attempt = findAttemptForClose(attemptId);
        if (!attempt.isOpen()) {
            logDiscarded(attempt, "agent 응답 status=" + response.status());
            return Optional.empty();
        }
        AdEvent event = attempt.getEvent();

        // 계약상 필수 값이 빠진 응답은 agent 호출 실패로 처리
        List<String> missing = missingFields(response);
        if (!missing.isEmpty()) {
            attempt.fail(FailedStage.AGENT_CALL, "agent 응답에 필수 값 없음: " + String.join(", ", missing));
            event.fail();
        } else {
            switch (response.status()) {
                case APPROVED -> {
                    attempt.succeed();
                    saveGeneration(attempt, response);
                }
                case REJECTED -> {
                    attempt.reject();
                    saveGeneration(attempt, response);
                    event.reject();
                }
                case FAILED -> {
                    attempt.fail(response.failedStage(), response.errorMessage());
                    event.fail();
                }
            }
        }
        log.info("[생성 시도 종료] eventId={}, attemptId={}, result={}", event.getId(), attemptId, attempt.getResult());

        String reviewReason = response.review() == null ? null : response.review().reason();
        return Optional.of(
                new GenerateResult(attempt.getResult(), attempt.getFailedStage(), attempt.getErrorMessage(), reviewReason));
    }

    // 승인·거절 응답에 반드시 있어야 하는 값 중 빠진 것
    private List<String> missingFields(AgentGenerateResponseDto response) {
        List<String> missing = new ArrayList<>();
        AgentGenerateResponseDto.Status status = response.status();
        if (status == AgentGenerateResponseDto.Status.FAILED) {
            return missing;
        }
        if (response.draft() == null) {
            missing.add("draft");
        }
        if (response.review() == null) {
            missing.add("review");
        }
        if (status == AgentGenerateResponseDto.Status.APPROVED && response.content() == null) {
            missing.add("content");
        }
        return missing;
    }

    // agent 호출 자체가 실패한 경우 (연결 실패, 비정상 응답 등). 이미 닫힌 시도면 저장하지 않고 false
    @Transactional
    public boolean failGenerate(Long attemptId, String errorMessage) {
        AdAttempt attempt = findAttemptForClose(attemptId);
        if (!attempt.isOpen()) {
            logDiscarded(attempt, "생성 실패: " + errorMessage);
            return false;
        }
        attempt.fail(FailedStage.AGENT_CALL, errorMessage);
        attempt.getEvent().fail();
        log.warn("[생성 시도 실패] attemptId={}, message={}", attemptId, errorMessage);
        return true;
    }

    // PUBLISH 시도를 기록하고 승인 콘텐츠를 반환
    @Transactional
    public PublishTarget startPublish(Long eventId, AttemptTrigger trigger) {
        AdEvent event = occupyEvent(eventId);
        AdAttempt approved = adAttemptRepository
                .findFirstByEventIdAndAttemptTypeAndResultOrderByStartedAtDescIdDesc(
                        eventId, AttemptType.GENERATE, AttemptResult.SUCCESS)
                .orElseThrow(() -> new IllegalStateException("승인된 생성 시도 없음: eventId=" + eventId));
        AdContent content = adGenerationRepository.findById(approved.getId())
                .orElseThrow(() -> new IllegalStateException("생성 결과 없음: attemptId=" + approved.getId()))
                .getContent();

        AdAttempt attempt = adAttemptRepository.save(AdAttempt.start(event, AttemptType.PUBLISH, trigger));
        return new PublishTarget(attempt.getId(), content);
    }

    // 발행 성공: 게시물 저장, 이벤트 PUBLISHED. 이미 닫힌 시도면 저장하지 않고 false
    @Transactional
    public boolean completePublish(Long attemptId, AdContent content, String postUrl) {
        AdAttempt attempt = findAttemptForClose(attemptId);
        if (!attempt.isOpen()) {
            // 게시는 됐으므로 추적할 수 있게 URL을 남김
            log.warn("[닫힌 시도의 발행 결과 버림] eventId={}, attemptId={}, result={}, url={}",
                    attempt.getEvent().getId(), attemptId, attempt.getResult(), postUrl);
            return false;
        }
        AdEvent event = attempt.getEvent();
        attempt.succeed();
        adPostRepository.save(AdPost.of(event, content, postUrl));
        event.publish();
        log.info("[발행 완료] eventId={}, attemptId={}, url={}", event.getId(), attemptId, postUrl);
        return true;
    }

    // 발행 실패: 이벤트 FAILED. 이미 닫힌 시도면 저장하지 않고 false
    @Transactional
    public boolean failPublish(Long attemptId, String errorMessage) {
        AdAttempt attempt = findAttemptForClose(attemptId);
        if (!attempt.isOpen()) {
            logDiscarded(attempt, "발행 실패: " + errorMessage);
            return false;
        }
        attempt.fail(FailedStage.PUBLISH, errorMessage);
        attempt.getEvent().fail();
        log.warn("[발행 실패] attemptId={}, message={}", attemptId, errorMessage);
        return true;
    }

    private void saveGeneration(AdAttempt attempt, AgentGenerateResponseDto response) {
        AgentGenerateResponseDto.Review review = response.review();
        AdGeneration generation = adGenerationRepository.save(AdGeneration.of(
                attempt,
                response.draft(),
                response.content(),
                review.reason(),
                review.selectedVote(),
                review.votes()
        ));
        if (review.issues() != null) {
            review.issues().forEach(issue -> adGenerationIssueRepository.save(
                    AdGenerationIssue.of(generation, issue.field(), issue.phrase(), issue.reason())));
        }
    }

    // 시도 시작 전 이벤트 선점: PENDING이고 진행 중인 시도가 없어야 하며, version 증가로 동시 시작을 막음
    private AdEvent occupyEvent(Long eventId) {
        AdEvent event = findEvent(eventId);
        if (adAttemptRepository.existsByEventIdAndResultIsNull(eventId)) {
            throw new IllegalStateException("진행 중인 시도 있음: eventId=" + eventId);
        }
        event.touch();
        return event;
    }

    private AdEvent findEvent(Long eventId) {
        return adEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("광고 이벤트 없음: eventId=" + eventId));
    }

    // 시도를 닫기 전에 행을 잠가 조회. 결과는 시도가 아직 열려 있을 때만 저장
    private AdAttempt findAttemptForClose(Long attemptId) {
        return adAttemptRepository.findWithLockById(attemptId)
                .orElseThrow(() -> new IllegalStateException("처리 시도 없음: attemptId=" + attemptId));
    }

    // 타임아웃 등으로 이미 닫힌 시도에 늦게 도착한 결과
    private void logDiscarded(AdAttempt attempt, String discarded) {
        log.info("[닫힌 시도의 결과 버림] eventId={}, attemptId={}, result={}, 버린 결과: {}",
                attempt.getEvent().getId(), attempt.getId(), attempt.getResult(), discarded);
    }

    // 특정 상품의 광고 이력 전체 조회
    /*
    * productId 있으면 해당 상품의 광고 이력만 반환
    * productId 없으면 전체 광고 이력 반환
    */
    @Transactional(readOnly = true)
    public List<AdvertisementResponseDto> findAdvertisements(Long productId) {
        List<AdPost> posts = (productId != null)
                ? adPostRepository.findByEventProductIdOrderByCreatedAtDesc(productId)
                : adPostRepository.findAllByOrderByCreatedAtDesc();

        return posts.stream()
                .map(post -> AdvertisementResponseDto.from(post, toJson(post.getContent())))
                .toList();
    }

    private String toJson(AdContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("광고 콘텐츠 직렬화 실패", e);
        }
    }
}