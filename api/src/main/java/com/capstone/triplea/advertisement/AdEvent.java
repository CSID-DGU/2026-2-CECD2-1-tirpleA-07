package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.value.ProductSnapshot;
import com.capstone.triplea.product.Product;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

// 광고 생성 요청 1건 (상품 등록·가격 인하 시 생성)
@Entity
@Table(
        name = "AD_EVENT",
        indexes = {
                @Index(name = "IDX_EVENT_STATUS_UPDATED", columnList = "status, updated_at"),
                @Index(name = "IDX_EVENT_PRODUCT_CREATED", columnList = "product_id, created_at")
        }
)
@SequenceGenerator(
        name = "AD_EVENT_SEQ_GENERATOR",
        sequenceName = "AD_EVENT_SEQ",
        initialValue = 1, allocationSize = 1
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdEvent {

    @Id
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "AD_EVENT_SEQ_GENERATOR"
    )
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;                    // 상품 id (FK)

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private AdEventType eventType;              // 이벤트 유형

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AdEventStatus status;               // 현재 상태

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "product_snapshot", nullable = false)
    private ProductSnapshot productSnapshot;    // 이벤트 시점 상품 정보

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;            // 마지막 처리 시각 (상태 변경, 시도 시작)

    @Version
    @Column(name = "version", nullable = false)
    private Long version;                       // 낙관적 락

    private AdEvent(Product product, AdEventType eventType, ProductSnapshot productSnapshot) {
        this.product = product;
        this.eventType = eventType;
        this.productSnapshot = productSnapshot;
        this.status = AdEventStatus.PENDING;
    }

    // 상품 커밋과 같은 트랜잭션에서 PENDING으로 생성
    public static AdEvent pending(Product product, AdEventType eventType, ProductSnapshot productSnapshot) {
        return new AdEvent(product, eventType, productSnapshot);
    }

    // 시도 시작 시 이벤트 선점: updated_at 갱신으로 version이 올라가 동시 시작을 막음
    public void touch() {
        requireStatus(AdEventStatus.PENDING);
        this.updatedAt = LocalDateTime.now();
    }

    public void publish() {
        changeStatus(AdEventStatus.PENDING, AdEventStatus.PUBLISHED);
    }

    public void reject() {
        changeStatus(AdEventStatus.PENDING, AdEventStatus.REJECTED);
    }

    public void fail() {
        changeStatus(AdEventStatus.PENDING, AdEventStatus.FAILED);
    }

    public void retry() {
        changeStatus(AdEventStatus.FAILED, AdEventStatus.PENDING);
    }

    private void changeStatus(AdEventStatus from, AdEventStatus to) {
        requireStatus(from);
        this.status = to;
        this.updatedAt = LocalDateTime.now();
    }

    private void requireStatus(AdEventStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateException(
                    "이벤트 상태가 " + expected + "가 아님: id=" + id + ", status=" + status);
        }
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }
}
