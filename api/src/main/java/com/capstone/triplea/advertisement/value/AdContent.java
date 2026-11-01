package com.capstone.triplea.advertisement.value;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// 광고 콘텐츠 (AD_GENERATION.draft / content, AD_POST.content에 JSON으로 저장)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdContent(
        String title,
        String body,
        String cta,
        List<String> hashtags,
        String imageUrl     // 생성 이미지 URL (이미지가 없으면 null)
) {
}
