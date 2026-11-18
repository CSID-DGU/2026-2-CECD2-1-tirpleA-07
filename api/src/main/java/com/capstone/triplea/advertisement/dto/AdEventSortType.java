package com.capstone.triplea.advertisement.dto;

import org.springframework.data.domain.Sort;

// 광고 이벤트 목록 정렬 기준 (같은 시각이면 id 순)
public enum AdEventSortType {
    CREATED_AT_DESC(Sort.Direction.DESC),   // 최신순 (기본)
    CREATED_AT_ASC(Sort.Direction.ASC);     // 오래된순

    private final Sort.Direction direction;

    AdEventSortType(Sort.Direction direction) {
        this.direction = direction;
    }

    public Sort toSort() {
        return Sort.by(direction, "createdAt").and(Sort.by(direction, "id"));
    }
}
