package com.capstone.triplea.advertisement;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AdEventRepository extends JpaRepository<AdEvent, Long> {

    // 상품에 연결된 이벤트 존재 여부 (상품 삭제 가능 여부 확인)
    boolean existsByProductId(Long productId);
}
