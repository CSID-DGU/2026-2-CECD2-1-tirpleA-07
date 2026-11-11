package com.capstone.triplea.advertisement.recovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// 자동 재시도·복구 설정 (ad.recovery.*)
@ConfigurationProperties("ad.recovery")
public record AdRecoveryProperties(
        @DefaultValue("true") boolean enabled,              // 복구 스케줄러 사용 여부 (끄면 자동 재시도 없이 첫 실패에서 FAILED)
        @DefaultValue("1m") Duration interval,              // 스케줄러 주기 (= 생성 재시도 간격)
        @DefaultValue("5m") Duration staleAfter,            // 미시작·열린 시도를 복구 대상으로 보는 기준
        @DefaultValue("3") int maxAutoRetries,              // 단계별 자동 재시도 횟수 (최초 시도 제외)
        @DefaultValue("false") boolean publishRetryEnabled, // 발행 자동 재시도 (게시 여부 확인이 들어간 뒤에 켬)
        @DefaultValue("5m") Duration publishCheckDelay,     // 발행 실패 후 다음 발행 시도까지 대기
        @DefaultValue("20") int batchSize                   // 한 주기에 실행하는 복구 건수 상한
) {
}
