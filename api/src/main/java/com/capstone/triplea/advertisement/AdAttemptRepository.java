package com.capstone.triplea.advertisement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdAttemptRepository extends JpaRepository<AdAttempt, Long> {

    // 이벤트의 시도 이력 (시간순)
    List<AdAttempt> findByEventIdOrderByStartedAtAscIdAsc(Long eventId);

    // 이벤트의 최신 시도 (다음 할 일 판단 기준)
    Optional<AdAttempt> findFirstByEventIdOrderByStartedAtDescIdDesc(Long eventId);

    // 이벤트의 최신 시도 (종류별)
    Optional<AdAttempt> findFirstByEventIdAndAttemptTypeOrderByStartedAtDescIdDesc(Long eventId, AttemptType attemptType);

    // 이벤트의 최신 시도 (종류·결과별) - 승인된 GENERATE 시도 조회 등
    Optional<AdAttempt> findFirstByEventIdAndAttemptTypeAndResultOrderByStartedAtDescIdDesc(
            Long eventId, AttemptType attemptType, AttemptResult result);

    // 진행 중인 시도 존재 여부 (시도 시작 전 확인)
    boolean existsByEventIdAndResultIsNull(Long eventId);
}
