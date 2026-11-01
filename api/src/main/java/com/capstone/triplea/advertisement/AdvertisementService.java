package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.dto.AdvertisementResponseDto;

import com.capstone.triplea.advertisement.dto.AgentGenerateRequestDto;
import com.capstone.triplea.advertisement.dto.AgentGenerateResponseDto;
import com.capstone.triplea.advertisement.value.AdContent;
import com.capstone.triplea.advertisement.value.ProductSnapshot;
import com.capstone.triplea.product.Product;
import com.capstone.triplea.product.ProductRepository;
import com.capstone.triplea.product.exception.ProductNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdvertisementService {
    private final AdvertisementRepository advertisementRepository;
    private final ProductRepository productRepository;
    private final AdEventRepository adEventRepository;
    private final AdAttemptRepository adAttemptRepository;
    private final AdGenerationRepository adGenerationRepository;
    private final AdGenerationIssueRepository adGenerationIssueRepository;
    private final AdPostRepository adPostRepository;
    private final ObjectMapper objectMapper;

    // 발행할 콘텐츠와 PUBLISH 시도 id
    public record PublishTarget(Long attemptId, AdContent content) {
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
    public AgentGenerateRequestDto startGenerate(Long eventId) {
        AdEvent event = findEvent(eventId);
        AdAttempt attempt = adAttemptRepository.save(AdAttempt.start(event, AttemptType.GENERATE, AttemptTrigger.AUTO));
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
    @Transactional
    public AttemptResult completeGenerate(Long attemptId, AgentGenerateResponseDto response) {
        AdAttempt attempt = findAttempt(attemptId);
        AdEvent event = attempt.getEvent();

        switch (response.status()) {
            case APPROVED -> {
                if (response.content() == null) {
                    attempt.fail(FailedStage.AGENT_CALL, "승인 응답에 콘텐츠 없음");
                    event.fail();
                    break;
                }
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
        log.info("[생성 시도 종료] eventId={}, attemptId={}, result={}", event.getId(), attemptId, attempt.getResult());
        return attempt.getResult();
    }

    // agent 호출 자체가 실패한 경우 (연결 실패, 비정상 응답 등)
    @Transactional
    public void failGenerate(Long attemptId, String errorMessage) {
        AdAttempt attempt = findAttempt(attemptId);
        attempt.fail(FailedStage.AGENT_CALL, errorMessage);
        attempt.getEvent().fail();
        log.warn("[생성 시도 실패] attemptId={}, message={}", attemptId, errorMessage);
    }

    // PUBLISH 시도를 기록하고 승인 콘텐츠를 반환
    @Transactional
    public PublishTarget startPublish(Long eventId) {
        AdEvent event = findEvent(eventId);
        AdAttempt approved = adAttemptRepository
                .findFirstByEventIdAndAttemptTypeAndResultOrderByStartedAtDescIdDesc(
                        eventId, AttemptType.GENERATE, AttemptResult.SUCCESS)
                .orElseThrow(() -> new IllegalStateException("승인된 생성 시도 없음: eventId=" + eventId));
        AdContent content = adGenerationRepository.findById(approved.getId())
                .orElseThrow(() -> new IllegalStateException("생성 결과 없음: attemptId=" + approved.getId()))
                .getContent();

        AdAttempt attempt = adAttemptRepository.save(AdAttempt.start(event, AttemptType.PUBLISH, AttemptTrigger.AUTO));
        return new PublishTarget(attempt.getId(), content);
    }

    // 발행 성공: 게시물 저장, 이벤트 PUBLISHED
    @Transactional
    public void completePublish(Long attemptId, AdContent content, String postUrl) {
        AdAttempt attempt = findAttempt(attemptId);
        AdEvent event = attempt.getEvent();
        attempt.succeed();
        adPostRepository.save(AdPost.of(event, content, postUrl));
        event.publish();
        log.info("[발행 완료] eventId={}, attemptId={}, url={}", event.getId(), attemptId, postUrl);
    }

    // 발행 실패: 이벤트 FAILED
    @Transactional
    public void failPublish(Long attemptId, String errorMessage) {
        AdAttempt attempt = findAttempt(attemptId);
        attempt.fail(FailedStage.PUBLISH, errorMessage);
        attempt.getEvent().fail();
        log.warn("[발행 실패] attemptId={}, message={}", attemptId, errorMessage);
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

    private AdEvent findEvent(Long eventId) {
        return adEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("광고 이벤트 없음: eventId=" + eventId));
    }

    private AdAttempt findAttempt(Long attemptId) {
        return adAttemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalStateException("처리 시도 없음: attemptId=" + attemptId));
    }

    // AI Agent 응답을 받아 광고 이력 저장
    @Transactional
    public AdvertisementResponseDto save(Long productId, AdEventType eventType, String adContent, String adUrl) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        Advertisement advertisement = Advertisement.builder()
                .product(product)
                .eventType(eventType)
                .adUrl(adUrl)
                .adContent(adContent)
                .build();
        Advertisement saved = advertisementRepository.save(advertisement);
        log.info("[광고 이력 저장] id={}, productId={}, eventType={}, adUrl={}", saved.getId(), productId, eventType, adUrl);

        return AdvertisementResponseDto.from(saved);
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