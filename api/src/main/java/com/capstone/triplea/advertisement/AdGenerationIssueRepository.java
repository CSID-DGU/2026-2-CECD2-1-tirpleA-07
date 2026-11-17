package com.capstone.triplea.advertisement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AdGenerationIssueRepository extends JpaRepository<AdGenerationIssue, Long> {

    // 생성 결과의 위반 사항 (표시 순서)
    List<AdGenerationIssue> findByGenerationAttemptIdOrderByIdAsc(Long attemptId);

    // 여러 생성 결과의 위반 사항 (표시 순서) - 이벤트 상세 조회
    List<AdGenerationIssue> findByGenerationAttemptIdInOrderByIdAsc(Collection<Long> attemptIds);
}
