package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.AdRetryService.RetryStarted;
import com.capstone.triplea.advertisement.dto.AdAttemptDto;
import com.capstone.triplea.advertisement.dto.AdAttemptSummaryDto;
import com.capstone.triplea.advertisement.dto.AdEventDetailDto;
import com.capstone.triplea.advertisement.dto.AdEventSortType;
import com.capstone.triplea.advertisement.dto.AdEventSummaryDto;
import com.capstone.triplea.advertisement.dto.AdGenerationDto;
import com.capstone.triplea.advertisement.dto.AgentGenerateRequestDto;
import com.capstone.triplea.advertisement.value.AdContent;
import com.capstone.triplea.advertisement.value.ProductSnapshot;
import com.capstone.triplea.global.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 광고 이벤트 API의 상태 코드와 응답 형식
class AdvertisementControllerTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 17, 14, 3, 21);
    private static final AdContent CONTENT = new AdContent("제목", "본문", "지금 예약", List.of("#다낭"), null);

    private final AdvertisementService advertisementService = mock(AdvertisementService.class);
    private final AdEventQueryService queryService = mock(AdEventQueryService.class);
    private final AdRetryService retryService = mock(AdRetryService.class);
    private final AdRetryRunner retryRunner = mock(AdRetryRunner.class);

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AdvertisementController(advertisementService, queryService, retryService, retryRunner))
            .setControllerAdvice(new GlobalExceptionHandler())
            // 앱과 같은 JSON 설정 (Spring Boot 기본값처럼 날짜를 ISO 문자열로)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                    .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build()))
            .build();

    @Test
    void 목록은_조건을_넘기고_페이지_형식으로_응답한다() throws Exception {
        AdAttemptSummaryDto latest = new AdAttemptSummaryDto(118L, AttemptType.PUBLISH, AttemptTrigger.RECOVERY,
                AttemptResult.FAILED, FailedStage.PUBLISH, "timeout", AT, AT.plusSeconds(1));
        when(queryService.findEvents(AdEventStatus.FAILED, 7L, AdEventSortType.CREATED_AT_ASC, 1, 5))
                .thenReturn(new PageImpl<>(List.of(new AdEventSummaryDto(41L, 7L, "그래놀라", AdEventType.NEW,
                        AdEventStatus.FAILED, AT, AT, 5, latest, null)), PageRequest.of(1, 5), 6));

        mvc.perform(get("/api/v1/advertisements/events")
                        .param("status", "FAILED").param("productId", "7")
                        .param("sort", "CREATED_AT_ASC").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.content[0].eventId").value(41))
                .andExpect(jsonPath("$.content[0].productName").value("그래놀라"))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-11-17T14:03:21"))
                .andExpect(jsonPath("$.content[0].attemptCount").value(5))
                .andExpect(jsonPath("$.content[0].latestAttempt.attemptId").value(118))
                .andExpect(jsonPath("$.content[0].latestAttempt.triggerType").value("RECOVERY"))
                .andExpect(jsonPath("$.content[0].latestAttempt.failedStage").value("PUBLISH"))
                .andExpect(jsonPath("$.content[0].postUrl").value(nullValue()));
    }

    @Test
    void 목록은_조건이_없으면_기본값으로_조회한다() throws Exception {
        when(queryService.findEvents(null, null, AdEventSortType.CREATED_AT_DESC, 0, 20))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/api/v1/advertisements/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
        verify(queryService).findEvents(null, null, AdEventSortType.CREATED_AT_DESC, 0, 20);
    }

    @Test
    void 목록의_잘못된_상태나_정렬_값은_400이다() throws Exception {
        mvc.perform(get("/api/v1/advertisements/events").param("status", "DONE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/advertisements/events").param("sort", "PRICE_ASC")).andExpect(status().isBadRequest());
    }

    @Test
    void 상세는_시도와_심의_근거와_재시도_가능_여부를_응답한다() throws Exception {
        AdGenerationDto generation = new AdGenerationDto(CONTENT, null, "허위 표현", 0,
                List.of(new AdGenerationDto.Vote(false, "허위 표현")),
                List.of(new AdGenerationDto.Issue("body", "100% 효과", "효능을 단정하는 표현")));
        AdAttemptDto attempt = new AdAttemptDto(101L, AttemptType.GENERATE, AttemptTrigger.AUTO,
                AttemptResult.REJECTED, null, null, AT, AT.plusSeconds(40), generation);
        ProductSnapshot snapshot = ProductSnapshot.ofNew(
                new ProductSnapshot.ProductInfo("그래놀라", "설명", 12000, 9900, 30, "식품", null));
        when(queryService.findEvent(41L)).thenReturn(new AdEventDetailDto(41L, 7L, AdEventType.NEW,
                AdEventStatus.FAILED, AT, AT, snapshot, List.of(attempt), null, false, AT.plusMinutes(5)));

        mvc.perform(get("/api/v1/advertisements/events/41"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(41))
                .andExpect(jsonPath("$.productSnapshot.productNew.name").value("그래놀라"))
                .andExpect(jsonPath("$.productSnapshot.productOld").value(nullValue()))
                .andExpect(jsonPath("$.attempts[0].result").value("REJECTED"))
                .andExpect(jsonPath("$.attempts[0].generation.draft.title").value("제목"))
                .andExpect(jsonPath("$.attempts[0].generation.draft.hashtags[0]").value("#다낭"))
                .andExpect(jsonPath("$.attempts[0].generation.content").value(nullValue()))
                .andExpect(jsonPath("$.attempts[0].generation.selectedVote").value(0))
                .andExpect(jsonPath("$.attempts[0].generation.votes[0].isAccepted").value(false))
                .andExpect(jsonPath("$.attempts[0].generation.votes[0].accepted").doesNotExist())
                .andExpect(jsonPath("$.attempts[0].generation.issues[0].phrase").value("100% 효과"))
                .andExpect(jsonPath("$.post").value(nullValue()))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.retryAvailableAt").value("2026-11-17T14:08:21"));
    }

    @Test
    void 없는_이벤트의_상세는_404와_코드를_응답한다() throws Exception {
        when(queryService.findEvent(99L)).thenThrow(new AdEventNotFoundException(99L));

        mvc.perform(get("/api/v1/advertisements/events/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"))
                .andExpect(jsonPath("$.retryAvailableAt").value(nullValue()));
    }

    @Test
    void 재시도를_시작하면_202로_응답하고_실행을_넘긴다() throws Exception {
        RetryStarted started = new RetryStarted.Generate(
                new AgentGenerateRequestDto(119L, 41L, AdEventType.NEW, null, null, false));
        when(retryService.start(41L)).thenReturn(started);

        mvc.perform(post("/api/v1/advertisements/events/41/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(41))
                .andExpect(jsonPath("$.attemptId").value(119))
                .andExpect(jsonPath("$.attemptType").value("GENERATE"));
        verify(retryRunner).run(started);
    }

    @Test
    void 발행부터_다시_하는_재시도는_PUBLISH_시도를_응답한다() throws Exception {
        RetryStarted started = new RetryStarted.Publish(
                41L, new AdvertisementService.PublishTarget(120L, CONTENT, AT));
        when(retryService.start(41L)).thenReturn(started);

        mvc.perform(post("/api/v1/advertisements/events/41/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attemptId").value(120))
                .andExpect(jsonPath("$.attemptType").value("PUBLISH"));
        verify(retryRunner).run(started);
    }

    @Test
    void 재시도할_수_없으면_409와_사유_코드를_응답하고_실행하지_않는다() throws Exception {
        when(retryService.start(1L)).thenThrow(
                new RetryRejectedException(RetryRejectedException.Code.NOT_FAILED, "FAILED 아님", (LocalDateTime) null));
        when(retryService.start(2L)).thenThrow(
                new RetryRejectedException(RetryRejectedException.Code.PUBLISH_WAIT, "대기 중", AT.plusMinutes(5)));
        when(retryService.start(3L)).thenThrow(
                new RetryRejectedException(RetryRejectedException.Code.CONFLICT, "선점됨", new RuntimeException()));
        // 커밋 시점에 알게 된 선점 충돌
        when(retryService.start(4L)).thenThrow(new OptimisticLockingFailureException("version"));

        mvc.perform(post("/api/v1/advertisements/events/1/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_FAILED"))
                .andExpect(jsonPath("$.retryAvailableAt").value(nullValue()));
        mvc.perform(post("/api/v1/advertisements/events/2/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUBLISH_WAIT"))
                .andExpect(jsonPath("$.retryAvailableAt").value("2026-11-17T14:08:21"));
        mvc.perform(post("/api/v1/advertisements/events/3/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(post("/api/v1/advertisements/events/4/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        verify(retryRunner, never()).run(any());
    }

    @Test
    void 없는_이벤트의_재시도는_404다() throws Exception {
        when(retryService.start(99L)).thenThrow(new AdEventNotFoundException(99L));

        mvc.perform(post("/api/v1/advertisements/events/99/retry"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }
}
