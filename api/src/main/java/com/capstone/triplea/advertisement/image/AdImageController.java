package com.capstone.triplea.advertisement.image;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

// 생성 광고 이미지 제공 (운영 콘솔 미리보기용). 파일명이 매번 새로 만들어져 내용이 바뀌지 않으므로 길게 캐시
@RestController
@Tag(name = "Advertisement Image", description = "생성 광고 이미지 API")
@RequiredArgsConstructor
public class AdImageController {

    private final AdImageStorage adImageStorage;

    @Operation(summary = "생성 광고 이미지 조회", description = "AdContent.imageUrl이 가리키는 이미지를 반환")
    @GetMapping(AdImageStorage.URL_PREFIX + "{fileName}")
    public ResponseEntity<Resource> getImage(@PathVariable String fileName) {
        return adImageStorage.find(fileName)
                .<ResponseEntity<Resource>>map(path -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                        .body(new FileSystemResource(path)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}