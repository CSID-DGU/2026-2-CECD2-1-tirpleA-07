package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.AdvertisementService.GenerateResult;
import com.capstone.triplea.advertisement.AdvertisementService.PublishTarget;
import com.capstone.triplea.advertisement.client.DiscordNotifier;
import com.capstone.triplea.advertisement.client.FacebookPublisher;
import com.capstone.triplea.advertisement.dto.AgentGenerateRequestDto;
import com.capstone.triplea.advertisement.value.AdContent;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdPipelineTest {

    private static final Long EVENT_ID = 10L;
    private static final Long GENERATE_ATTEMPT_ID = 1L;
    private static final Long PUBLISH_ATTEMPT_ID = 2L;
    private static final AdContent CONTENT = new AdContent("제목", "본문", "지금 예약", List.of("#다낭"), null);
    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 11, 14, 12, 0);
    private static final String NEW_URL = "https://www.facebook.com/111/posts/9";
    private static final String FOUND_URL = "https://www.facebook.com/111/posts/2";
    private static final String LINKED_URL = "https://www.facebook.com/111/posts/1";

    private final AdvertisementService service = mock(AdvertisementService.class);
    private final FacebookPublisher facebook = mock(FacebookPublisher.class);
    private final DiscordNotifier discord = mock(DiscordNotifier.class);

    // agent 응답을 고정한 파이프라인
    private AdPipeline pipeline(HttpStatus status, String agentResponse) {
        WebClient agent = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(status)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(agentResponse)
                        .build()))
                .build();
        return new AdPipeline(agent, service, facebook, discord);
    }

    private AdPipeline pipeline() {
        return pipeline(HttpStatus.OK, "{\"attemptId\":1,\"status\":\"APPROVED\"}");
    }

    private void publishTarget(LocalDateTime verifySince) {
        when(service.startPublish(eq(EVENT_ID), any()))
                .thenReturn(new PublishTarget(PUBLISH_ATTEMPT_ID, CONTENT, verifySince));
    }

    private void generateStarts() {
        when(service.startGenerate(eq(EVENT_ID), any())).thenReturn(new AgentGenerateRequestDto(
                GENERATE_ATTEMPT_ID, EVENT_ID, AdEventType.NEW, null, null, false));
    }

    private void generateCompletes(AttemptResult result, AdEventStatus eventStatus) {
        FailedStage stage = result == AttemptResult.FAILED ? FailedStage.REVIEW : null;
        when(service.completeGenerate(eq(GENERATE_ATTEMPT_ID), any())).thenReturn(Optional.of(
                new GenerateResult(result, stage, stage == null ? null : "심의 실패", "심의 사유", eventStatus)));
    }

    // ---- 발행 ----

    @Test
    void 첫_발행은_게시_여부를_조회하지_않고_게시한다() {
        publishTarget(null);
        when(facebook.publish(CONTENT)).thenReturn(NEW_URL);
        when(service.completePublish(PUBLISH_ATTEMPT_ID, CONTENT, NEW_URL)).thenReturn(true);

        pipeline().publish(EVENT_ID, AttemptTrigger.AUTO);

        verify(facebook, never()).findPostUrls(any(), any());
        verify(service).completePublish(PUBLISH_ATTEMPT_ID, CONTENT, NEW_URL);
        verify(discord).notifyPublished(EVENT_ID, "제목", NEW_URL);
    }

    @Test
    void 이미_게시된_것을_확인하면_재발행하지_않고_그_게시물로_확정한다() {
        publishTarget(SINCE);
        when(facebook.findPostUrls(SINCE, CONTENT)).thenReturn(List.of(LINKED_URL, FOUND_URL));
        when(service.findUnlinkedPostUrl(List.of(LINKED_URL, FOUND_URL))).thenReturn(Optional.of(FOUND_URL));
        when(service.completePublish(PUBLISH_ATTEMPT_ID, CONTENT, FOUND_URL)).thenReturn(true);

        pipeline().publish(EVENT_ID, AttemptTrigger.RECOVERY);

        verify(facebook, never()).publish(any());
        verify(service).completePublish(PUBLISH_ATTEMPT_ID, CONTENT, FOUND_URL);
        verify(discord).notifyPublished(EVENT_ID, "제목", FOUND_URL);
    }

    @Test
    void 게시되지_않은_것을_확인하면_다시_게시한다() {
        publishTarget(SINCE);
        when(facebook.findPostUrls(SINCE, CONTENT)).thenReturn(List.of());
        when(service.findUnlinkedPostUrl(List.of())).thenReturn(Optional.empty());
        when(facebook.publish(CONTENT)).thenReturn(NEW_URL);
        when(service.completePublish(PUBLISH_ATTEMPT_ID, CONTENT, NEW_URL)).thenReturn(true);

        pipeline().publish(EVENT_ID, AttemptTrigger.RECOVERY);

        verify(facebook).publish(CONTENT);
        verify(discord).notifyPublished(EVENT_ID, "제목", NEW_URL);
    }

    @Test
    void 같은_메시지의_게시물이_모두_다른_이벤트에_연결돼_있으면_다시_게시한다() {
        publishTarget(SINCE);
        when(facebook.findPostUrls(SINCE, CONTENT)).thenReturn(List.of(LINKED_URL));
        when(service.findUnlinkedPostUrl(List.of(LINKED_URL))).thenReturn(Optional.empty());
        when(facebook.publish(CONTENT)).thenReturn(NEW_URL);
        when(service.completePublish(PUBLISH_ATTEMPT_ID, CONTENT, NEW_URL)).thenReturn(true);

        pipeline().publish(EVENT_ID, AttemptTrigger.RECOVERY);

        verify(facebook).publish(CONTENT);
        verify(service).completePublish(PUBLISH_ATTEMPT_ID, CONTENT, NEW_URL);
    }

    @Test
    void 게시_여부를_확인할_수_없으면_게시하지_않고_실패로_기록한다() {
        publishTarget(SINCE);
        when(facebook.findPostUrls(SINCE, CONTENT)).thenThrow(new IllegalStateException("403 Forbidden"));
        when(service.failPublish(eq(PUBLISH_ATTEMPT_ID), anyString())).thenReturn(CloseResult.RETRY_PENDING);

        pipeline().publish(EVENT_ID, AttemptTrigger.RECOVERY);

        verify(facebook, never()).publish(any());
        verify(service).failPublish(eq(PUBLISH_ATTEMPT_ID), startsWith("게시 여부 확인 불가"));
        verify(service, never()).completePublish(anyLong(), any(), any());
        verifyNoInteractions(discord);
    }

    @Test
    void 발행_실패_알림은_재시도를_모두_썼을_때만_보낸다() {
        publishTarget(null);
        when(facebook.publish(CONTENT)).thenThrow(new IllegalStateException("timeout"));
        when(service.failPublish(eq(PUBLISH_ATTEMPT_ID), anyString()))
                .thenReturn(CloseResult.RETRY_PENDING, CloseResult.EVENT_FAILED);

        pipeline().publish(EVENT_ID, AttemptTrigger.AUTO);
        verifyNoInteractions(discord);

        pipeline().publish(EVENT_ID, AttemptTrigger.RECOVERY);
        verify(discord).notifyFailed(eq(EVENT_ID), eq("PUBLISH"), anyString());
    }

    @Test
    void 닫힌_시도의_발행_결과는_알리지_않는다() {
        publishTarget(null);
        when(facebook.publish(CONTENT)).thenReturn(NEW_URL);
        when(service.completePublish(PUBLISH_ATTEMPT_ID, CONTENT, NEW_URL)).thenReturn(false);

        pipeline().publish(EVENT_ID, AttemptTrigger.AUTO);

        verifyNoInteractions(discord);
    }

    @Test
    void 게시_후_저장에_실패하면_URL을_담아_실패로_기록한다() {
        publishTarget(null);
        when(facebook.publish(CONTENT)).thenReturn(NEW_URL);
        when(service.completePublish(PUBLISH_ATTEMPT_ID, CONTENT, NEW_URL)).thenThrow(new IllegalStateException("DB"));
        when(service.failPublish(eq(PUBLISH_ATTEMPT_ID), anyString())).thenReturn(CloseResult.RETRY_PENDING);

        pipeline().publish(EVENT_ID, AttemptTrigger.AUTO);

        verify(service).failPublish(eq(PUBLISH_ATTEMPT_ID), startsWith("게시 후 저장 실패 (url=" + NEW_URL + ")"));
    }

    // ---- 생성 ----

    @Test
    void 승인되면_같은_trigger로_발행까지_이어간다() {
        generateStarts();
        generateCompletes(AttemptResult.SUCCESS, AdEventStatus.PENDING);
        publishTarget(null);
        when(facebook.publish(CONTENT)).thenReturn(NEW_URL);
        when(service.completePublish(PUBLISH_ATTEMPT_ID, CONTENT, NEW_URL)).thenReturn(true);

        pipeline().generate(EVENT_ID, AttemptTrigger.RECOVERY);

        verify(service).startGenerate(EVENT_ID, AttemptTrigger.RECOVERY);
        verify(service).startPublish(EVENT_ID, AttemptTrigger.RECOVERY);
        verify(discord).notifyPublished(EVENT_ID, "제목", NEW_URL);
    }

    @Test
    void 거절되면_거절_알림만_보내고_발행하지_않는다() {
        generateStarts();
        generateCompletes(AttemptResult.REJECTED, AdEventStatus.REJECTED);

        pipeline().generate(EVENT_ID, AttemptTrigger.AUTO);

        verify(discord).notifyRejected(EVENT_ID, "심의 사유");
        verify(service, never()).startPublish(anyLong(), any());
    }

    @Test
    void 생성_실패_알림은_재시도를_모두_썼을_때만_보낸다() {
        generateStarts();
        generateCompletes(AttemptResult.FAILED, AdEventStatus.PENDING);
        pipeline().generate(EVENT_ID, AttemptTrigger.AUTO);
        verifyNoInteractions(discord);

        generateCompletes(AttemptResult.FAILED, AdEventStatus.FAILED);
        pipeline().generate(EVENT_ID, AttemptTrigger.RECOVERY);
        verify(discord).notifyFailed(EVENT_ID, "REVIEW", "심의 실패");
        verify(service, never()).startPublish(anyLong(), any());
    }

    @Test
    void 닫힌_시도에_온_agent_응답이면_아무것도_하지_않는다() {
        generateStarts();
        when(service.completeGenerate(eq(GENERATE_ATTEMPT_ID), any())).thenReturn(Optional.empty());

        pipeline().generate(EVENT_ID, AttemptTrigger.AUTO);

        verify(service, never()).startPublish(anyLong(), any());
        verifyNoInteractions(discord);
    }

    @Test
    void agent_호출이_실패하면_생성_실패로_기록한다() {
        generateStarts();
        when(service.failGenerate(eq(GENERATE_ATTEMPT_ID), anyString())).thenReturn(CloseResult.RETRY_PENDING);

        pipeline(HttpStatus.BAD_GATEWAY, "{}").generate(EVENT_ID, AttemptTrigger.AUTO);

        verify(service).failGenerate(eq(GENERATE_ATTEMPT_ID), anyString());
        verify(service, never()).completeGenerate(anyLong(), any());
        verifyNoInteractions(discord);
    }
}
