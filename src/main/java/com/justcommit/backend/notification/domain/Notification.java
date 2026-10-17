package com.justcommit.backend.notification.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "notification", indexes = @Index(name = "idx_notification_member_read", columnList = "memberId, isRead"))
@NoArgsConstructor
@Getter
public class Notification extends BaseTimeEntity {

    // 알림을 받는 회원
    @Column(nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false)
    private String message;

    // 알림을 눌렀을 때 이동할 판매자 주문
    private Long sellerOrderId;

    @Column(nullable = false)
    private boolean isRead;

    private LocalDateTime readAt;

    // 이 알림을 만든 이벤트. Kafka가 같은 메시지를 두 번 줘도 알림이 한 건만 생기게 한다.
    @Column(unique = true, nullable = false, length = 100)
    private String eventKey;

    public Notification(Long memberId, NotificationType type, Long sellerOrderId, String eventKey) {
        this.memberId = memberId;
        this.type = type;
        this.message = type.getMessage();
        this.sellerOrderId = sellerOrderId;
        this.eventKey = eventKey;
        this.isRead = false;
    }

    // 이미 읽은 알림이면 처음 읽은 시각을 유지한다
    public void read(LocalDateTime readAt) {
        if (this.isRead) {
            return;
        }
        this.isRead = true;
        this.readAt = readAt;
    }
}
