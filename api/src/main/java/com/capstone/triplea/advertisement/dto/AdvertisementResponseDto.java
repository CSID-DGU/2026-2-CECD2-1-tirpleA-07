package com.capstone.triplea.advertisement.dto;

import com.capstone.triplea.advertisement.AdEventType;
import com.capstone.triplea.advertisement.AdPost;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AdvertisementResponseDto {
    private Long id;
    private Long productId;
    private AdEventType eventType;
    private String adUrl;
    private String adContent;
    private LocalDateTime createdAt;

    // 게시물(AD_POST) 기준 응답. 기존 응답 형식을 유지하기 위해 콘텐츠는 JSON 문자열로 내려줌
    public static AdvertisementResponseDto from(AdPost post, String adContent) {
        return AdvertisementResponseDto.builder()
                .id(post.getId())
                .productId(post.getEvent().getProduct().getId())
                .eventType(post.getEvent().getEventType())
                .adUrl(post.getAdUrl())
                .adContent(adContent)
                .createdAt(post.getCreatedAt())
                .build();
    }
}
