package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.dto.AdEventDetailDto;
import com.capstone.triplea.advertisement.dto.AdEventSortType;
import com.capstone.triplea.advertisement.dto.AdEventSummaryDto;
import com.capstone.triplea.advertisement.dto.AdvertisementResponseDto;
import com.capstone.triplea.advertisement.dto.RetryAcceptedDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/advertisements")
@Tag(name = "Advertisement", description = "광고 이력 API")
@RequiredArgsConstructor
public class AdvertisementController {
    private final AdvertisementService advertisementService;
    private final AdEventQueryService adEventQueryService;
    private final AdRetryService adRetryService;
    private final AdRetryRunner adRetryRunner;

    @Operation(
            summary = "광고 이력 전체 조회",
            description = "productId 없이 호출하면 전체 광고 이력, productId를 넘기면 해당 상품의 광고 이력을 반환"
    )
    @GetMapping
    public ResponseEntity<List<AdvertisementResponseDto>> getAdvertisements(
            @Parameter(description = "상품 id (선택)")
            @RequestParam(required = false) Long productId) {
        return ResponseEntity.ok(advertisementService.findAdvertisements(productId));
    }

    @Operation(
            summary = "광고 이벤트 목록 조회",
            description = "상태와 상관없이 광고 이벤트를 조회 (실패·대기·반려 포함). "
                    + "status: PENDING | PUBLISHED | REJECTED | FAILED, sort: CREATED_AT_DESC(기본) | CREATED_AT_ASC"
    )
    @GetMapping("/events")
    public ResponseEntity<Page<AdEventSummaryDto>> getEvents(
            @Parameter(description = "이벤트 상태 (선택)")
            @RequestParam(required = false) AdEventStatus status,
            @Parameter(description = "상품 id (선택)")
            @RequestParam(required = false) Long productId,
            @RequestParam(defaultValue = "CREATED_AT_DESC") AdEventSortType sort,
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (최대 100)")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adEventQueryService.findEvents(status, productId, sort, page, size));
    }

    @Operation(
            summary = "광고 이벤트 상세 조회",
            description = "시도 이력, 시도별 생성 결과와 심의 근거(초안, 판정 사유, 투표, 위반 항목), 게시물, 수동 재시도 가능 여부를 반환"
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "이벤트 없음")
    @GetMapping("/events/{eventId}")
    public ResponseEntity<AdEventDetailDto> getEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(adEventQueryService.findEvent(eventId));
    }

    @Operation(
            summary = "광고 이벤트 수동 재시도",
            description = "FAILED 이벤트를 다시 처리. 승인된 생성 결과가 있으면 발행부터(게시 여부 확인 포함), 없으면 생성부터 시작. "
                    + "시도를 시작한 뒤 바로 응답하고 처리는 이어서 진행되므로 결과는 상세 조회로 확인"
    )
    @ApiResponse(responseCode = "202", description = "재시도 시작")
    @ApiResponse(responseCode = "404", description = "이벤트 없음")
    @ApiResponse(responseCode = "409",
            description = "재시도 불가. code: NOT_FAILED(FAILED가 아님) | PUBLISH_WAIT(발행 실패 후 대기 중) | CONFLICT(다른 요청이 먼저 시작)")
    @PostMapping("/events/{eventId}/retry")
    public ResponseEntity<RetryAcceptedDto> retryEvent(@PathVariable Long eventId) {
        AdRetryService.RetryStarted started;
        try {
            started = adRetryService.start(eventId);
        } catch (OptimisticLockingFailureException e) {
            // 커밋 시점에 알게 된 선점 충돌
            throw new RetryRejectedException(RetryRejectedException.Code.CONFLICT,
                    "다른 요청이 먼저 재시도를 시작함: eventId=" + eventId, e);
        }
        // 시도 시작이 커밋된 뒤 실행을 넘김
        adRetryRunner.run(started);
        return ResponseEntity.accepted()
                .body(new RetryAcceptedDto(started.eventId(), started.attemptId(), started.attemptType()));
    }
}
