package com.capstone.triplea.advertisement;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdPostRepository extends JpaRepository<AdPost, Long> {

    Optional<AdPost> findByEventId(Long eventId);

    // 특정 상품의 게시물 (최신순)
    @EntityGraph(attributePaths = "event")
    List<AdPost> findByEventProductIdOrderByCreatedAtDesc(Long productId);

    // 전체 게시물 (최신순)
    @EntityGraph(attributePaths = "event")
    List<AdPost> findAllByOrderByCreatedAtDesc();
}
