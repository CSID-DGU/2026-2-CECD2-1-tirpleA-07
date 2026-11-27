package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.dto.AdAttemptDto;
import com.capstone.triplea.advertisement.dto.AdAttemptSummaryDto;
import com.capstone.triplea.advertisement.dto.AdEventDetailDto;
import com.capstone.triplea.advertisement.dto.AdGenerationDto;
import com.capstone.triplea.advertisement.dto.AdPostDto;
import com.capstone.triplea.advertisement.dto.AdEventSortType;
import com.capstone.triplea.advertisement.dto.AdEventSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 광고 이벤트 조회 (운영 콘솔용). 상태를 바꾸지 않음
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdEventQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdEventRepository adEventRepository;
    private final AdAttemptRepository adAttemptRepository;
    private final AdPostRepository adPostRepository;
    private final AdGenerationRepository adGenerationRepository;
    private final AdGenerationIssueRepository adGenerationIssueRepository;
    private final AdRetryService adRetryService;

    // 이벤트 목록. status, productId는 없으면 전체
    // 시도와 게시물은 페이지의 이벤트 id로 한 번씩만 조회
    public Page<AdEventSummaryDto> findEvents(
            AdEventStatus status, Long productId, AdEventSortType sort, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), sort.toSort());
        Page<AdEvent> events = findEventPage(status, productId, pageable);

        List<Long> eventIds = events.getContent().stream().map(AdEvent::getId).toList();
        // 이벤트별 시도 (시작 순서 오름차순)
        Map<Long, List<AdAttempt>> attempts = eventIds.isEmpty() ? Map.of()
                : adAttemptRepository.findByEventIdInOrderByStartedAtAscIdAsc(eventIds).stream()
                        .collect(Collectors.groupingBy(attempt -> attempt.getEvent().getId()));
        Map<Long, String> postUrls = eventIds.isEmpty() ? Map.of()
                : adPostRepository.findByEventIdIn(eventIds).stream()
                        .collect(Collectors.toMap(post -> post.getEvent().getId(), AdPost::getAdUrl));

        return events.map(event -> toSummary(
                event, attempts.getOrDefault(event.getId(), List.of()), postUrls.get(event.getId())));
    }

    // 이벤트 상세: 시도 이력, 시도별 생성 결과와 심의 근거, 게시물, 재시도 가능 여부
    public AdEventDetailDto findEvent(Long eventId) {
        AdEvent event = adEventRepository.findById(eventId)
                .orElseThrow(() -> new AdEventNotFoundException(eventId));
        List<AdAttempt> attempts = adAttemptRepository.findByEventIdOrderByStartedAtAscIdAsc(eventId);

        List<Long> attemptIds = attempts.stream().map(AdAttempt::getId).toList();
        Map<Long, AdGeneration> generations = adGenerationRepository.findAllById(attemptIds).stream()
                .collect(Collectors.toMap(AdGeneration::getAttemptId, Function.identity()));
        Map<Long, List<AdGenerationIssue>> issues = generations.isEmpty() ? Map.of()
                : adGenerationIssueRepository.findByGenerationAttemptIdInOrderByIdAsc(generations.keySet()).stream()
                        .collect(Collectors.groupingBy(issue -> issue.getGeneration().getAttemptId()));

        AdRetryService.RetryCheck retry = adRetryService.check(event, LocalDateTime.now());
        return new AdEventDetailDto(
                event.getId(),
                event.getProduct().getId(),
                event.getEventType(),
                event.getStatus(),
                event.getCreatedAt(),
                event.getUpdatedAt(),
                event.getProductSnapshot(),
                attempts.stream()
                        .map(attempt -> AdAttemptDto.from(attempt, toGeneration(
                                generations.get(attempt.getId()), issues.getOrDefault(attempt.getId(), List.of()))))
                        .toList(),
                adPostRepository.findByEventId(eventId).map(AdPostDto::from).orElse(null),
                retry.allowed(),
                retry.availableAt()
        );
    }

    private static AdGenerationDto toGeneration(AdGeneration generation, List<AdGenerationIssue> issues) {
        return generation == null ? null : AdGenerationDto.from(generation, issues);
    }

    private Page<AdEvent> findEventPage(AdEventStatus status, Long productId, Pageable pageable) {
        if (status != null && productId != null) {
            return adEventRepository.findByStatusAndProductId(status, productId, pageable);
        }
        if (status != null) {
            return adEventRepository.findByStatus(status, pageable);
        }
        if (productId != null) {
            return adEventRepository.findByProductId(productId, pageable);
        }
        return adEventRepository.findAll(pageable);
    }

    private static AdEventSummaryDto toSummary(AdEvent event, List<AdAttempt> attempts, String postUrl) {
        AdAttempt latest = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
        return new AdEventSummaryDto(
                event.getId(),
                event.getProduct().getId(),
                event.getProductSnapshot().productNew().name(),
                event.getEventType(),
                event.getStatus(),
                event.getCreatedAt(),
                event.getUpdatedAt(),
                attempts.size(),
                latest == null ? null : AdAttemptSummaryDto.from(latest),
                postUrl
        );
    }
}
