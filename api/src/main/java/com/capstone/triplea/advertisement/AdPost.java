package com.capstone.triplea.advertisement;

import com.capstone.triplea.advertisement.value.AdContent;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

// 발행에 성공한 게시물 (이벤트 1건에 0~1건)
@Entity
@Table(
        name = "AD_POST",
        uniqueConstraints = {
                @UniqueConstraint(name = "UK_POST_EVENT", columnNames = "event_id"),
                @UniqueConstraint(name = "UK_POST_URL", columnNames = "ad_url")
        }
)
@SequenceGenerator(
        name = "AD_POST_SEQ_GENERATOR",
        sequenceName = "AD_POST_SEQ",
        initialValue = 1, allocationSize = 1
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdPost {

    @Id
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "AD_POST_SEQ_GENERATOR"
    )
    @Column(name = "id")
    private Long id;

    // 채널 추가 시 이벤트 1건에 게시물 여러 건이 될 수 있어 ManyToOne + UNIQUE로 매핑
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private AdEvent event;              // 이벤트 id (FK)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false)
    private AdContent content;          // 게시된 콘텐츠

    @Column(name = "ad_url", nullable = false, length = 255)
    private String adUrl;               // 게시글 URL

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;    // 게시 시각

    private AdPost(AdEvent event, AdContent content, String adUrl) {
        this.event = event;
        this.content = content;
        this.adUrl = adUrl;
    }

    public static AdPost of(AdEvent event, AdContent content, String adUrl) {
        return new AdPost(event, content, adUrl);
    }

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
