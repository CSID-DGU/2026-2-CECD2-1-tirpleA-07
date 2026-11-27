package com.capstone.triplea.advertisement.dto;

import com.capstone.triplea.advertisement.AdPost;
import com.capstone.triplea.advertisement.value.AdContent;

import java.time.LocalDateTime;

// 게시물 (이벤트 상세)
public record AdPostDto(
        Long postId,
        String adUrl,
        AdContent content,
        LocalDateTime createdAt
) {
    public static AdPostDto from(AdPost post) {
        return new AdPostDto(post.getId(), post.getAdUrl(), post.getContent(), post.getCreatedAt());
    }
}
