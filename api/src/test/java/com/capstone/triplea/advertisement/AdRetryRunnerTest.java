package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.AdRetryService.RetryStarted;
import com.capstone.triplea.advertisement.dto.AgentGenerateRequestDto;
import com.capstone.triplea.advertisement.value.AdContent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class AdRetryRunnerTest {

    private final AdPipeline pipeline = mock(AdPipeline.class);
    private final AdRetryRunner runner = new AdRetryRunner(pipeline);

    private final AgentGenerateRequestDto request =
            new AgentGenerateRequestDto(119L, 41L, AdEventType.NEW, null, null, false);
    private final AdvertisementService.PublishTarget target = new AdvertisementService.PublishTarget(
            120L, new AdContent("제목", "본문", "지금 예약", List.of(), null), LocalDateTime.of(2026, 11, 17, 14, 0));

    @Test
    void 이미_시작된_시도를_새로_시작하지_않고_실행만_한다() {
        runner.run(new RetryStarted.Generate(request));
        runner.run(new RetryStarted.Publish(41L, target));

        verify(pipeline).runGenerate(request, AttemptTrigger.MANUAL);
        verify(pipeline).runPublish(41L, target);
        verifyNoMoreInteractions(pipeline);
    }

    @Test
    void 실행_중_예외는_밖으로_던지지_않는다() {
        doThrow(new IllegalStateException("DB 연결 끊김")).when(pipeline).runGenerate(request, AttemptTrigger.MANUAL);

        assertThatCode(() -> runner.run(new RetryStarted.Generate(request))).doesNotThrowAnyException();
    }
}
