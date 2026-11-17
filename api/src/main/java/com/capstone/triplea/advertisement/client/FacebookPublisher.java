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
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
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

    // 게시 여부 확인 때 한 페이지에 읽는 게시물 수
    private static final int LOOKUP_LIMIT = 100;

    // 게시 여부 확인 때 읽는 최대 페이지 수. 넘으면 확인 불가
    private static final int MAX_LOOKUP_PAGES = 10;

    private final WebClient webClient;
    private final URI apiUri;
    private final String pageId;
    private final String token;

    public FacebookPublisher(
            WebClient.Builder webClientBuilder,
            @Value("${facebook.api-url}") String apiUrl,
            @Value("${facebook.page-id}") String pageId,
            @Value("${facebook.token}") String token) {
        this.webClient = webClientBuilder.baseUrl(apiUrl).build();
        this.apiUri = URI.create(apiUrl);
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
    // 한 페이지를 넘으면 다음 페이지를 이어서 읽고, 일부만 읽은 상태로는 결과를 단정하지 않음
    // 문구가 같은 다른 이벤트의 게시물이 섞일 수 있으므로, 어느 것을 쓸지는 호출하는 쪽이 정함
    // 토큰은 헤더로 보내 요청 URL(예외 메시지에 포함됨)에 남지 않게 함
    public List<String> findPostUrls(LocalDateTime since, AdContent content) {
        long sinceEpoch = since.minus(SINCE_MARGIN).atZone(ZoneId.systemDefault()).toEpochSecond();

        // 게시할 때와 같은 방식으로 만든 메시지와 정규화 후 완전 일치하는 게시물만 같은 것으로 봄
        String expected = normalize(buildMessage(content));
        List<JsonNode> matches = new ArrayList<>();

        JsonNode response = webClient.get()
                .uri("/{pageId}/published_posts?since={since}&fields=id,message,created_time&limit={limit}",
                        pageId, sinceEpoch, LOOKUP_LIMIT)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TIMEOUT);

        for (int page = 1; ; page++) {
            JsonNode data = response == null ? null : response.get("data");
            if (data == null || !data.isArray()) {
                throw new IllegalStateException("Facebook 게시물 조회 응답에 data 없음");
            }
            for (JsonNode post : data) {
                if (post.hasNonNull("id") && post.get("id").asText().contains("_")
                        && post.hasNonNull("message") && normalize(post.get("message").asText()).equals(expected)) {
                    matches.add(post);
                }
            }

            JsonNode next = response.path("paging").get("next");
            if (next == null || next.isNull()) {
                break;
            }
            if (page >= MAX_LOOKUP_PAGES) {
                throw new IllegalStateException(
                        "게시물 조회 범위를 모두 확인하지 못함 (" + MAX_LOOKUP_PAGES * LOOKUP_LIMIT + "건 초과)");
            }
            response = webClient.get()
                    .uri(nextPageUri(next.asText()))
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TIMEOUT);
        }

        // 먼저 게시된 순서 (created_time은 같은 형식의 UTC 문자열)
        matches.sort(Comparator.comparing(post -> post.path("created_time").asText("")));
        if (matches.size() > 1) {
            log.warn("[게시 확인] 같은 메시지의 게시물이 {}건: {}", matches.size(),
                    matches.stream().map(post -> post.get("id").asText()).collect(Collectors.joining(", ")));
        }
        return matches.stream().map(post -> toPostUrl(post.get("id").asText())).toList();
    }

    // 다음 페이지 주소(paging.next)는 Facebook이 준 값이므로, 설정한 Graph API와 같은 scheme·host일 때만 요청함
    // (토큰을 다른 곳에 보내지 않기 위함). 경로는 비교하지 않음 (설정 끝의 / 유무, 버전 경로 차이에 영향받지 않게)
    // 주소에 access_token이 있으면 빼고 요청함. 토큰은 헤더로만 보내고, 요청이 실패해도 예외 메시지(URL 포함)에 남지 않게 함
    // (헤더로 조회하면 Facebook이 next에 토큰을 넣지 않는 것을 확인했지만, 형식이 바뀌어도 새지 않도록)
    // 주소 자체는 예외 메시지에 남기지 않음
    private URI nextPageUri(String next) {
        URI uri;
        try {
            uri = URI.create(next);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("게시물 조회의 다음 페이지 주소 형식이 올바르지 않음");
        }
        if (!sameOrigin(uri, apiUri)) {
            throw new IllegalStateException("게시물 조회의 다음 페이지 주소가 Graph API 주소가 아님");
        }
        return UriComponentsBuilder.fromUri(uri).replaceQueryParam("access_token").build(true).toUri();
    }

    private static boolean sameOrigin(URI target, URI base) {
        return target.getScheme() != null && target.getScheme().equalsIgnoreCase(base.getScheme())
                && target.getHost() != null && target.getHost().equalsIgnoreCase(base.getHost())
                && target.getPort() == base.getPort()
                && target.getRawUserInfo() == null;
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
