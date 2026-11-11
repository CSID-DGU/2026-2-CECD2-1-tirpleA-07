package com.capstone.triplea.advertisement;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // 시도를 닫을 때 쓰는 잠금 조회: 타임아웃 닫기와 늦은 응답 저장이 겹쳐도 한쪽은 닫힌 상태를 보게 함
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AdAttempt a where a.id = :id")
    Optional<AdAttempt> findWithLockById(@Param("id") Long id);

    // 진행 중인 시도 존재 여부 (시도 시작 전 확인)
    boolean existsByEventIdAndResultIsNull(Long eventId);
}
