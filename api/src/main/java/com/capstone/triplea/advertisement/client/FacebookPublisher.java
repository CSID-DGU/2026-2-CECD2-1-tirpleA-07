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
import java.util.stream.Collectors;

// Facebook 페이지 게시와 게시 여부 확인 (게시는 agent의 sns_adapter에서 이전)
@Slf4j
@Component
public class FacebookPublisher {

    // Facebook 응답 대기 시간. 넘으면 예외 (게시됐을 수 있으므로 재발행 전 확인 필요)
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // 게시물 조회 시작 시각을 앞당기는 여유 (api 서버와 Facebook의 시계 차이)
    private static final Duration SINCE_MARGIN = Duration.ofMinutes(1);

    // 게시 여부 확인 때 한 번에 읽는 게시물 수. 다음 페이지는 읽지 않음
    private static final int LOOKUP_LIMIT = 100;

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

    // since 이후 페이지가 게시한 글(published_posts)에서 콘텐츠와 같은 메시지의 게시물을 모두 찾아 게시글 URL을 반환
    // (/feed는 방문자 글까지 포함해 pages_read_engagement만으로는 읽을 수 없음)
    // 먼저 게시된 순서로 반환하고 없으면 빈 목록. 확인할 수 없으면(조회 실패, 응답 이상, 읽지 못한 게시물이 남음) 예외
    // 문구가 같은 다른 이벤트의 게시물이 섞일 수 있으므로, 어느 것을 쓸지는 호출하는 쪽이 정함
    // 토큰은 헤더로 보내 요청 URL(예외 메시지에 포함됨)에 남지 않게 함
    public List<String> findPostUrls(LocalDateTime since, AdContent content) {
        long sinceEpoch = since.minus(SINCE_MARGIN).atZone(ZoneId.systemDefault()).toEpochSecond();

        JsonNode response = webClient.get()
                .uri("/{pageId}/published_posts?since={since}&fields=id,message,created_time&limit={limit}",
                        pageId, sinceEpoch, LOOKUP_LIMIT)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TIMEOUT);

        JsonNode data = response == null ? null : response.get("data");
        if (data == null || !data.isArray()) {
            throw new IllegalStateException("Facebook 게시물 조회 응답에 data 없음");
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

        // 다음 페이지는 읽지 않음 (조회 범위가 짧아 한 페이지를 넘을 일이 없음). 다 읽지 못했으면 결과를 단정하지 않음
        if (response.path("paging").hasNonNull("next")) {
            throw new IllegalStateException("게시물 조회 범위를 모두 확인하지 못함 (" + LOOKUP_LIMIT + "건 초과)");
        }

        // 먼저 게시된 순서 (created_time은 같은 형식의 UTC 문자열)
        matches.sort(Comparator.comparing(post -> post.path("created_time").asText("")));
        if (matches.size() > 1) {
            log.warn("[게시 확인] 같은 메시지의 게시물이 {}건: {}", matches.size(),
                    matches.stream().map(post -> post.get("id").asText()).collect(Collectors.joining(", ")));
        }
        return matches.stream().map(post -> toPostUrl(post.get("id").asText())).toList();
    }

    // 비교용 정규화. 보낸 메시지와 Facebook에 저장된 메시지를 같은 형태로 맞춤
    // - Facebook이 저장하면서 바꾸는 것 (테스트 페이지에서 확인): 끝의 줄바꿈 제거, 연속된 빈 줄을 한 줄로 축소, 탭을 공백으로 변경
    // - 그 밖에 내용과 무관한 차이도 함께 무시: 줄바꿈 종류(\r\n, \r), 각 줄 끝의 공백, 전체 앞뒤 공백
    // 처리 순서: 줄바꿈 통일 -> 탭을 공백으로 -> 각 줄 끝 공백 제거 -> 연속된 빈 줄 축소 -> 전체 앞뒤 공백·빈 줄 제거
    static String normalize(String message) {
        String unified = message.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
        return Arrays.stream(unified.split("\n", -1))
                .map(String::stripTrailing)
                .collect(Collectors.joining("\n"))
                .replaceAll("\n{3,}", "\n\n")
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
