package com.capstone.triplea.advertisement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AdEventRepository extends JpaRepository<AdEvent, Long> {

    // 상품에 연결된 이벤트 존재 여부 (상품 삭제 가능 여부 확인)
    boolean existsByProductId(Long productId);

    // 상태별 이벤트 id (오래 처리되지 않은 순) - 복구 스케줄러의 처리 대상 조회
    @Query("select e.id from AdEvent e where e.status = :status order by e.updatedAt asc, e.id asc")
    List<Long> findIdsByStatusOrderByUpdatedAtAsc(@Param("status") AdEventStatus status);
}
