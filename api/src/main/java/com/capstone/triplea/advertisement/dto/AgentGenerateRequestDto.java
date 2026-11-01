package com.capstone.triplea.advertisement.dto;

import com.capstone.triplea.advertisement.AdEventType;
import com.capstone.triplea.advertisement.value.ProductSnapshot;

// agent /generate 요청
public record AgentGenerateRequestDto(
        Long attemptId,                         // 시도 id (응답에 그대로 돌아옴)
        Long eventId,                           // 이벤트 id (agent 로그·Discord 메시지용)
        AdEventType eventType,
        ProductSnapshot.ProductInfo productNew,
        ProductSnapshot.ProductInfo productOld, // DISCOUNT일 때만
        boolean isSample
) {
}
