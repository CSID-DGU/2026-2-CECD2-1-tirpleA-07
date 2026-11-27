package com.capstone.triplea.advertisement;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AdPostRepository extends JpaRepository<AdPost, Long> {

    Optional<AdPost> findByEventId(Long eventId);

    // 여러 이벤트의 게시물 - 이벤트 목록 조회
    List<AdPost> findByEventIdIn(Collection<Long> eventIds);

    // 게시글이 이미 어떤 이벤트에 연결돼 있는지 (게시 여부 확인 때 다른 이벤트의 게시물 제외)
    boolean existsByAdUrl(String adUrl);

    // 특정 상품의 게시물 (최신순)
    @EntityGraph(attributePaths = "event")
    List<AdPost> findByEventProductIdOrderByCreatedAtDesc(Long productId);

    // 전체 게시물 (최신순)
    @EntityGraph(attributePaths = "event")
    List<AdPost> findAllByOrderByCreatedAtDesc();
}
