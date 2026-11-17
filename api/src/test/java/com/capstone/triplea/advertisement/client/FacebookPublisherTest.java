package com.capstone.triplea.advertisement.client;

import com.capstone.triplea.advertisement.value.AdContent;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FacebookPublisherTest {

    private static final String TOKEN = "test-token-value";
    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 11, 14, 12, 0, 0);
    private static final AdContent CONTENT =
            new AdContent("다낭 특가", "4박 5일 일정", "지금 예약하세요", List.of("#다낭", "#특가"), null);
    private static final String MESSAGE = "다낭 특가\\n4박 5일 일정\\n지금 예약하세요\\n#다낭 #특가";

    private static final String NEXT = "https://graph.example.com/v1/111/published_posts";

    private final List<ClientRequest> requests = new ArrayList<>();

    // 정해진 응답을 돌려주는 가짜 Facebook
    private FacebookPublisher publisher(HttpStatus status, String body) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(ClientResponse.create(status)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        });
        return new FacebookPublisher(builder, "https://graph.example.com/v1", "111", TOKEN);
    }

    private static String post(String id, String message, String createdTime) {
        String messageField = message == null ? "" : "\"message\":\"" + message + "\",";
        return "{\"id\":\"" + id + "\"," + messageField + "\"created_time\":\"" + createdTime + "\"}";
    }

    private static String postList(String... posts) {
        return "{\"data\":[" + String.join(",", posts) + "]}";
    }

    // 게시물 목록 한 페이지. next가 있으면 다음 페이지 주소를 포함
    private static String page(String next, String... posts) {
        String paging = next == null ? "" : ",\"paging\":{\"next\":\"" + next + "\"}";
        return "{\"data\":[" + String.join(",", posts) + "]" + paging + "}";
    }

    private static ClientResponse response(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    // 요청 순서대로 정해진 응답을 돌려주는 가짜 Facebook (응답이 모자라면 마지막 응답을 반복)
    private FacebookPublisher publisher(String... bodies) {
        return publisher(java.util.Arrays.stream(bodies).map(body -> response(HttpStatus.OK, body)).toList());
    }

    private FacebookPublisher publisher(List<ClientResponse> responses) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(responses.get(Math.min(requests.size(), responses.size()) - 1));
        });
        return new FacebookPublisher(builder, "https://graph.example.com/v1", "111", TOKEN);
    }

    @Test
    void 같은_메시지의_게시물이_있으면_URL을_반환한다() {
        FacebookPublisher publisher = publisher(HttpStatus.OK, postList(
                post("111_1", "다른 광고", "2026-11-14T03:05:00+0000"),
                post("111_2", MESSAGE, "2026-11-14T03:01:00+0000")));

        assertThat(publisher.findPostUrls(SINCE, CONTENT)).containsExactly("https://www.facebook.com/111/posts/2");
    }

    @Test
    void 줄_끝_공백과_끝의_빈_줄_차이는_같은_메시지로_본다() {
        FacebookPublisher publisher = publisher(HttpStatus.OK, postList(
                post("111_2", "다낭 특가 \\r\\n4박 5일 일정\\n지금 예약하세요\\n#다낭 #특가\\n\\n", "2026-11-14T03:01:00+0000")));

        assertThat(publisher.findPostUrls(SINCE, CONTENT)).containsExactly("https://www.facebook.com/111/posts/2");
    }

    @Test
    void 같은_메시지가_없으면_빈_값을_반환한다() {
        FacebookPublisher publisher = publisher(HttpStatus.OK, postList(
                post("111_1", "다낭 특가\\n4박 6일 일정\\n지금 예약하세요\\n#다낭 #특가", "2026-11-14T03:05:00+0000"),
                post("111_3", null, "2026-11-14T03:02:00+0000")));

        assertThat(publisher.findPostUrls(SINCE, CONTENT)).isEmpty();
        assertThat(publisher(HttpStatus.OK, postList()).findPostUrls(SINCE, CONTENT)).isEmpty();
    }

    @Test
    void 같은_메시지가_여러_건이면_먼저_게시된_순서로_모두_반환한다() {
        FacebookPublisher publisher = publisher(HttpStatus.OK, postList(
                post("111_9", MESSAGE, "2026-11-14T03:10:00+0000"),
                post("111_5", MESSAGE, "2026-11-14T03:01:00+0000")));

        assertThat(publisher.findPostUrls(SINCE, CONTENT)).containsExactly(
                "https://www.facebook.com/111/posts/5", "https://www.facebook.com/111/posts/9");
    }

    @Test
    void 다음_페이지가_있으면_이어서_읽고_모든_페이지에서_찾는다() {
        FacebookPublisher publisher = publisher(
                page(NEXT + "?after=a", post("111_9", MESSAGE, "2026-11-14T03:10:00+0000")),
                page(NEXT + "?after=b", post("111_7", "다른 광고", "2026-11-14T03:05:00+0000")),
                page(null, post("111_5", MESSAGE, "2026-11-14T03:01:00+0000")));

        assertThat(publisher.findPostUrls(SINCE, CONTENT)).containsExactly(
                "https://www.facebook.com/111/posts/5", "https://www.facebook.com/111/posts/9");
        assertThat(requests).hasSize(3);
        assertThat(requests.get(1).url().toString()).isEqualTo(NEXT + "?after=a");
        assertThat(requests.get(2).url().toString()).isEqualTo(NEXT + "?after=b");
    }

    @Test
    void 다음_페이지_요청에도_토큰은_헤더로만_보낸다() {
        publisher(page(NEXT + "?after=a"), page(null)).findPostUrls(SINCE, CONTENT);

        ClientRequest second = requests.get(1);
        assertThat(second.url().toString()).doesNotContain(TOKEN).doesNotContain("access_token");
        assertThat(second.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void 열_페이지를_읽고도_다음_페이지가_남으면_확인_불가다() {
        String[] pages = new String[11];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = page(NEXT + "?after=" + i, post("111_" + (i + 1), MESSAGE, "2026-11-14T03:01:00+0000"));
        }

        assertThatThrownBy(() -> publisher(pages).findPostUrls(SINCE, CONTENT))
                .isInstanceOf(IllegalStateException.class);
        assertThat(requests).hasSize(10);
    }

    @Test
    void 열_번째_페이지가_마지막이면_확인된다() {
        String[] pages = new String[10];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = page(i == 9 ? null : NEXT + "?after=" + i);
        }

        assertThat(publisher(pages).findPostUrls(SINCE, CONTENT)).isEmpty();
        assertThat(requests).hasSize(10);
    }

    @Test
    void 다음_페이지_주소가_Graph_API_주소가_아니면_요청하지_않고_확인_불가다() {
        for (String next : List.of(
                "https://evil.example.com/v1/111/published_posts?after=a",
                "https://graph.example.com/v1.evil.example.com/x",
                "https://graph.example.com/v1")) {
            requests.clear();
            FacebookPublisher publisher = publisher(page(next, post("111_2", MESSAGE, "2026-11-14T03:01:00+0000")), page(null));

            assertThatThrownBy(() -> publisher.findPostUrls(SINCE, CONTENT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("evil");
            assertThat(requests).hasSize(1);
        }
    }

    @Test
    void 다음_페이지_조회가_실패하면_앞_페이지에_일치가_있어도_확인_불가다() {
        FacebookPublisher failing = publisher(List.of(
                response(HttpStatus.OK, page(NEXT + "?after=a", post("111_2", MESSAGE, "2026-11-14T03:01:00+0000"))),
                response(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":{\"message\":\"x\"}}")));
        assertThatThrownBy(() -> failing.findPostUrls(SINCE, CONTENT))
                .isInstanceOf(WebClientResponseException.class)
                .hasMessageNotContaining(TOKEN);

        FacebookPublisher broken = publisher(
                page(NEXT + "?after=a", post("111_2", MESSAGE, "2026-11-14T03:01:00+0000")),
                "{\"error\":{\"message\":\"x\"}}");
        assertThatThrownBy(() -> broken.findPostUrls(SINCE, CONTENT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 응답에_data가_없으면_확인_불가다() {
        assertThatThrownBy(() -> publisher(HttpStatus.OK, "{\"error\":{\"message\":\"x\"}}").findPostUrls(SINCE, CONTENT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 오류_응답이면_확인_불가이고_예외_메시지에_토큰이_없다() {
        for (HttpStatus status : List.of(HttpStatus.FORBIDDEN, HttpStatus.INTERNAL_SERVER_ERROR)) {
            assertThatThrownBy(() -> publisher(status, "{\"error\":{\"message\":\"denied\"}}").findPostUrls(SINCE, CONTENT))
                    .isInstanceOf(WebClientResponseException.class)
                    .hasMessageNotContaining(TOKEN);
        }
    }

    @Test
    void 토큰은_헤더로_보내고_조회_시작_시각은_1분_앞당긴다() {
        publisher(HttpStatus.OK, postList()).findPostUrls(SINCE, CONTENT);

        ClientRequest request = requests.get(0);
        long expectedSince = SINCE.minusMinutes(1).atZone(ZoneId.systemDefault()).toEpochSecond();
        String url = request.url().toString();
        assertThat(url).startsWith("https://graph.example.com/v1/111/published_posts?")
                .contains("since=" + expectedSince)
                .contains("limit=100")
                .contains("fields=id,message,created_time")
                .doesNotContain(TOKEN)
                .doesNotContain("access_token");
        assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void 정규화는_줄바꿈과_줄_끝_공백과_앞뒤_빈_줄만_맞춘다() {
        assertThat(FacebookPublisher.normalize("\n제목  \r\n본문\t\r끝\n\n")).isEqualTo("제목\n본문\n끝");
        assertThat(FacebookPublisher.normalize("제목\n\n본문")).isEqualTo("제목\n\n본문");
        assertThat(FacebookPublisher.normalize("제목\n 본문")).isNotEqualTo(FacebookPublisher.normalize("제목\n본문"));
        assertThat(FacebookPublisher.normalize("제목  본문")).isNotEqualTo(FacebookPublisher.normalize("제목 본문"));
    }

    // 테스트 페이지에 실제로 게시한 뒤 조회한 값 (2026-11-14): 끝 줄바꿈 제거, 빈 줄 두 개 -> 한 개, 탭 -> 공백
    @Test
    void Facebook이_저장하면서_바꾼_메시지도_같은_메시지로_본다() {
        String sent = "두바이 5일   \n첫 줄 끝에 공백이 있음  \n\n\n빈 줄 두 개 뒤의 문장\tCTA 탭 포함 \n#두바이  #사막";
        String stored = "두바이 5일   \n첫 줄 끝에 공백이 있음  \n\n빈 줄 두 개 뒤의 문장 CTA 탭 포함 \n#두바이  #사막";
        assertThat(FacebookPublisher.normalize(stored)).isEqualTo(FacebookPublisher.normalize(sent));

        String sentWithoutHashtags = "그리스 산토리니 7일\n에게해의 석양을 만나 보세요.\n지금 예약하기\n";
        String storedWithoutHashtags = "그리스 산토리니 7일\n에게해의 석양을 만나 보세요.\n지금 예약하기";
        assertThat(FacebookPublisher.normalize(storedWithoutHashtags)).isEqualTo(FacebookPublisher.normalize(sentWithoutHashtags));
    }
}
