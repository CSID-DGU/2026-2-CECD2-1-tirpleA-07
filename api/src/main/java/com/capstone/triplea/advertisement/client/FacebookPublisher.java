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

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

// Facebook 페이지 게시와 게시 여부 확인 (게시는 agent의 sns_adapter에서 이전)
@Slf4j
@Component
public class FacebookPublisher {

    // Facebook 응답 대기 시간. 넘으면 예외 (게시됐을 수 있으므로 재발행 전 확인 필요)
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // 피드 조회 시작 시각을 앞당기는 여유 (api 서버와 Facebook의 시계 차이)
    private static final Duration SINCE_MARGIN = Duration.ofMinutes(1);

    // 피드에서 한 번에 읽는 게시물 수. 다음 페이지는 읽지 않음
    private static final int FEED_LIMIT = 100;

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
                .block(TIMEOUT);

        String postId = (response == null || !response.hasNonNull("id")) ? null : response.get("id").asText();
        if (postId == null || !postId.contains("_")) {
            throw new IllegalStateException("Facebook 응답에 게시물 id 없음: " + response);
        }
        return toPostUrl(postId);
    }

    // since 이후 페이지 피드에서 콘텐츠와 같은 메시지의 게시물을 찾아 게시글 URL을 반환
    // 있으면 URL, 없으면 빈 값. 확인할 수 없으면(조회 실패, 응답 이상, 읽지 못한 게시물이 남음) 예외
    // 토큰은 헤더로 보내 요청 URL(예외 메시지에 포함됨)에 남지 않게 함
    public Optional<String> findPostUrl(LocalDateTime since, AdContent content) {
        long sinceEpoch = since.minus(SINCE_MARGIN).atZone(ZoneId.systemDefault()).toEpochSecond();

        JsonNode response = webClient.get()
                .uri("/{pageId}/feed?since={since}&fields=id,message,created_time&limit={limit}",
                        pageId, sinceEpoch, FEED_LIMIT)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TIMEOUT);

        JsonNode data = response == null ? null : response.get("data");
        if (data == null || !data.isArray()) {
            throw new IllegalStateException("Facebook 피드 응답에 data 없음");
        }

        // 게시할 때와 같은 방식으로 만든 메시지와 정규화 후 완전 일치하는 게시물만 같은 것으로 봄
        String expected = normalize(buildMessage(content));
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode post : data) {
            if (post.hasNonNull("id") && post.get("id").asText().contains("_")
                    && post.hasNonNull("message") && normalize(post.get("message").asText()).equals(expected)) {
                matches.add(post);
            }
        }

        if (!matches.isEmpty()) {
            // 이미 중복 게시된 경우에는 가장 먼저 게시된 것을 사용 (created_time은 같은 형식의 UTC 문자열)
            matches.sort(Comparator.comparing(post -> post.path("created_time").asText("")));
            if (matches.size() > 1) {
                log.warn("[게시 확인] 같은 메시지의 게시물이 {}건: {}", matches.size(),
                        matches.stream().map(post -> post.get("id").asText()).collect(Collectors.joining(", ")));
            }
            return Optional.of(toPostUrl(matches.get(0).get("id").asText()));
        }

        // 다음 페이지 URL에는 토큰이 들어 있어 따라가지 않음. 다 읽지 못했으면 없다고 단정하지 않음
        if (response.path("paging").hasNonNull("next")) {
            throw new IllegalStateException("피드 조회 범위를 모두 확인하지 못함 (게시물 " + FEED_LIMIT + "건 초과)");
        }
        return Optional.empty();
    }

    // 비교용 정규화: 줄바꿈 통일, 각 줄 끝 공백 제거, 전체 앞뒤 공백·빈 줄 제거
    static String normalize(String message) {
        return Arrays.stream(message.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1))
                .map(String::stripTrailing)
                .collect(Collectors.joining("\n"))
                .strip();
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
