package com.justcommit.backend.notification.domain;

import com.justcommit.backend.notification.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "notification", indexes = {
        @Index(name = "idx_notification_member_read_created", columnList = "memberId, isRead, createdAt"),
        @Index(name = "idx_notification_role_read_created", columnList = "recipientRole, isRead, createdAt")})
@NoArgsConstructor
@Getter
public class Notification extends BaseTimeEntity {

    // 알림을 받는 회원. 역할 알림이면 null (받는 사람은 회원 · 역할 중 하나, V3 체크 제약)
    private Long memberId;

    // 역할 전체가 받는 알림(관리자 검수 지연)의 받는 역할. 회원 알림이면 null. 읽음은 그 역할 회원 모두가 함께 씀
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RecipientRole recipientRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false)
    private String message;

    // 알림을 눌렀을 때 이동할 주문 (구매자는 주문 상세, 판매자는 판매 주문 상세). 없는 알림은 null
    private Long orderId;

    private Long sellerOrderId;

    // 알림을 눌렀을 때 이동할 상품 (검수 통과 · 반려 알림). 주문 알림은 null
    private Long productId;

    // 어떤 주문의 알림인지 구분하는 상품 요약 (예: "몬스테라 외 1건"). 만들 때의 값을 보존하고, 모르면 null
    private String itemSummary;

    @Column(nullable = false)
    private boolean isRead;

    private LocalDateTime readAt;

    // 이 알림을 만든 이벤트. Kafka가 같은 메시지를 두 번 줘도 알림이 한 건만 생기게 한다.
    @Column(unique = true, nullable = false, length = 100)
    private String eventKey;

    public Notification(Long memberId, NotificationType type, Long orderId, Long sellerOrderId, String itemSummary,
                        String eventKey) {
        this(memberId, type, type.getMessage(), orderId, sellerOrderId, null, itemSummary, eventKey);
    }

    // 문구가 이벤트마다 다른 알림(검수 반려 사유)이나 상품으로 이동하는 알림
    public Notification(Long memberId, NotificationType type, String message, Long orderId, Long sellerOrderId,
                        Long productId, String itemSummary, String eventKey) {
        this.memberId = memberId;
        this.type = type;
        this.message = message;
        this.orderId = orderId;
        this.sellerOrderId = sellerOrderId;
        this.productId = productId;
        this.itemSummary = itemSummary;
        this.eventKey = eventKey;
        this.isRead = false;
    }

    // 역할 전체가 받는 알림 (member_id 없음, 이동할 주문 · 상품 없음)
    public static Notification forRole(RecipientRole role, NotificationType type, String message, String eventKey) {
        Notification notification = new Notification(null, type, message, null, null, null, null, eventKey);
        notification.recipientRole = role;
        return notification;
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
