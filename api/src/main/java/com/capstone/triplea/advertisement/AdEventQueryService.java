package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.dto.AdAttemptSummaryDto;
import com.capstone.triplea.advertisement.dto.AdEventSortType;
import com.capstone.triplea.advertisement.dto.AdEventSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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
