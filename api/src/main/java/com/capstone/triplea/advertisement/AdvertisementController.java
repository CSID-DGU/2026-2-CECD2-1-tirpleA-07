package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.dto.AdEventSortType;
import com.capstone.triplea.advertisement.dto.AdEventSummaryDto;
import com.capstone.triplea.advertisement.dto.AdvertisementResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
}
