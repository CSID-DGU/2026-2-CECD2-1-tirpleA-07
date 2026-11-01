package com.capstone.triplea.advertisement.client;

import com.capstone.triplea.advertisement.value.AdContent;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

// Facebook 페이지 게시 (agent의 sns_adapter에서 이전)
@Slf4j
@Component
public class FacebookPublisher {

    private final WebClient webClient;
    private final String pageId;
    private final String token;

    public FacebookPublisher(
            WebClient.Builder webClientBuilder,
            @Value("${facebook.api-url}") String apiUrl,
            @Value("${facebook.page-id}") String pageId,
            @Value("${facebook.token}") String token) {
        this.webClient = webClientBuilder.baseUrl(apiUrl).build();
        this.pageId = pageId;
        this.token = token;
    }

    // 콘텐츠를 페이지 피드에 게시하고 게시글 URL을 반환 (실패 시 예외)
    public String publish(AdContent content) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("message", buildMessage(content));
        form.add("access_token", token);

        JsonNode response = webClient.post()
                .uri("/{pageId}/feed", pageId)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        String postId = (response == null || !response.hasNonNull("id")) ? null : response.get("id").asText();
        if (postId == null || !postId.contains("_")) {
            throw new IllegalStateException("Facebook 응답에 게시물 id 없음: " + response);
        }
        return toPostUrl(postId);
    }

    // 제목, 본문, CTA, 해시태그를 줄바꿈으로 연결
    private String buildMessage(AdContent content) {
        List<String> hashtags = content.hashtags() == null ? List.of() : content.hashtags();
        return String.join("\n",
                nullToEmpty(content.title()),
                nullToEmpty(content.body()),
                nullToEmpty(content.cta()),
                String.join(" ", hashtags));
    }

    // 게시물 id ({pageId}_{postId}) -> 게시글 URL
    private String toPostUrl(String postId) {
        String[] ids = postId.split("_", 2);
        return "https://www.facebook.com/" + ids[0] + "/posts/" + ids[1];
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
