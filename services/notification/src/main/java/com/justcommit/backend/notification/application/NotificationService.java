package com.justcommit.backend.notification.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.notification.client.market.ShipmentQuery;
import com.justcommit.backend.notification.client.market.ShipmentResult;
import com.justcommit.backend.notification.domain.Notification;
import com.justcommit.backend.notification.domain.NotificationErrorCode;
import com.justcommit.backend.notification.domain.NotificationType;
import com.justcommit.backend.notification.domain.RecipientRole;
import com.justcommit.backend.notification.infrastructure.repository.NotificationRepository;
import com.justcommit.backend.notification.infrastructure.sse.NotificationSseRegistry;
import com.justcommit.backend.notification.presentation.dto.NotificationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    // 시간이 지나면 연결을 닫고, 브라우저가 다시 연결한다
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;
    // notification.message 칸 길이 (V1 varchar(255))
    private static final int MESSAGE_MAX_LENGTH = 255;

    private final NotificationRepository notificationRepository;
    private final NotificationSseRegistry sseRegistry;
    private final ShipmentQuery shipmentQuery;

    // 주문 id 없이 만드는 알림 (판매자 알림은 sellerOrderId로 판매 주문 상세로 이동)
    @Transactional
    public void create(Long memberId, NotificationType type, Long sellerOrderId, String eventKey) {
        create(memberId, type, null, sellerOrderId, eventKey);
    }

    // 상품 요약을 모르는 이벤트(주문 쪽 이벤트)는 판매자 주문의 배송에 저장된 상품 요약을 조회해 쓴다
    @Transactional
    public void create(Long memberId, NotificationType type, Long orderId, Long sellerOrderId, String eventKey) {
        save(memberId, type, orderId, sellerOrderId, () -> itemSummaryOf(sellerOrderId), eventKey);
    }

    // 상품 요약을 이벤트로 받은 경우(배송 이벤트)는 조회하지 않는다
    @Transactional
    public void create(Long memberId, NotificationType type, Long orderId, Long sellerOrderId, String itemSummary,
                       String eventKey) {
        save(memberId, type, orderId, sellerOrderId, () -> itemSummary, eventKey);
    }

    // 검수 통과 · 반려 알림: 누르면 상품으로 이동하고, 상품명을 상품 요약 칸에 둔다.
    // detail(반려 사유)이 있으면 문구 뒤에 붙인다. 알림 문구 칸 길이(255)를 넘으면 자른다
    @Transactional
    public void createForProduct(Long memberId, NotificationType type, String detail, Long productId,
                                 String productTitle, String eventKey) {
        String message = (detail == null || detail.isBlank())
                ? type.getMessage()
                : type.getMessage() + " 사유: " + detail;
        save(memberId, type, eventKey, () -> new Notification(memberId, type, truncate(message), null, null,
                productId, productTitle, eventKey));
    }

    private void save(Long memberId, NotificationType type, Long orderId, Long sellerOrderId,
                      Supplier<String> itemSummary, String eventKey) {
        save(memberId, type, eventKey,
                () -> new Notification(memberId, type, orderId, sellerOrderId, itemSummary.get(), eventKey));
    }

    // 이벤트로 알림을 만든다. 같은 이벤트가 다시 오면(Kafka 중복 전달) 새로 만들지 않는다.
    private void save(Long memberId, NotificationType type, String eventKey, Supplier<Notification> notification) {
        if (memberId == null) {
            // 받는 사람을 모르는 이벤트 (예: 구매자 id를 저장하기 전에 만든 배송). 다시 받아도 같으므로 건너뛴다.
            log.warn("받는 사람이 없어 알림을 만들지 않음: type={}, eventKey={}", type, eventKey);
            return;
        }
        if (notificationRepository.existsByEventKey(eventKey)) {
            log.info("이미 만든 알림 (중복 이벤트): eventKey={}", eventKey);
            return;
        }
        notificationRepository.save(notification.get());
        pushUnreadCountAfterCommit(memberId);
    }

    private static String truncate(String message) {
        return message.length() <= MESSAGE_MAX_LENGTH ? message : message.substring(0, MESSAGE_MAX_LENGTH);
    }

    // 역할 전체가 받는 알림 (관리자 검수 지연): member_id 없이 1건, 그 역할 회원 누구나 보고 읽음은 함께 쓴다 (#561)
    @Transactional
    public void createForRole(RecipientRole role, NotificationType type, String message, String eventKey) {
        if (notificationRepository.existsByEventKey(eventKey)) {
            log.info("이미 만든 알림 (중복 이벤트): eventKey={}", eventKey);
            return;
        }
        notificationRepository.save(Notification.forRole(role, type, truncate(message), eventKey));
        pushUnreadCountAfterCommit(sseRegistry.membersWithRole(role));
    }

    // 아래 조회 · 읽음은 모두 「나에게 온 알림 + 내 역할이 받는 역할 알림」 기준 (관리자가 아니면 나에게 온 알림만)
    @Transactional(readOnly = true)
    public PagingResponse<NotificationResponse> list(AuthMember viewer, Boolean isRead, Pageable pageable) {
        RecipientRole role = RecipientRole.of(viewer.role());
        Page<Notification> page = (isRead == null)
                ? notificationRepository.findVisible(viewer.memberId(), role, pageable)
                : notificationRepository.findVisibleByIsRead(viewer.memberId(), role, isRead, pageable);
        return PagingResponse.from(page.map(NotificationResponse::from));
    }

    @Transactional(readOnly = true)
    public long unreadCount(AuthMember viewer) {
        return notificationRepository.countVisibleUnread(viewer.memberId(), RecipientRole.of(viewer.role()));
    }

    // 역할 알림을 읽으면 그 역할 회원 모두에게 읽음이 되므로, 연결 중인 같은 역할 회원의 숫자도 맞춘다
    @Transactional
    public NotificationResponse read(AuthMember viewer, Long notificationId) {
        RecipientRole role = RecipientRole.of(viewer.role());
        Notification notification = notificationRepository.findVisibleById(notificationId, viewer.memberId(), role)
                .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        notification.read(LocalDateTime.now());
        pushUnreadCountAfterCommit(affectedBy(viewer.memberId(), notification.getRecipientRole()));
        return NotificationResponse.from(notification);
    }

    @Transactional
    public void readAll(AuthMember viewer) {
        RecipientRole role = RecipientRole.of(viewer.role());
        notificationRepository.readAll(viewer.memberId(), role, LocalDateTime.now());
        pushUnreadCountAfterCommit(affectedBy(viewer.memberId(), role));
    }

    // 실시간 구독. 연결하자마자 지금 안 읽은 알림 수를 한 번 보낸다. 역할을 기억해 역할 알림도 받게 한다
    public SseEmitter subscribe(AuthMember viewer) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sseRegistry.add(viewer.memberId(), RecipientRole.of(viewer.role()), emitter, unreadCount(viewer));
        return emitter;
    }

    // 읽음이 바뀌어 숫자를 다시 보낼 회원: 나 + (역할 알림이면) 연결 중인 같은 역할 회원
    private Set<Long> affectedBy(Long memberId, RecipientRole role) {
        Set<Long> members = new HashSet<>();
        members.add(memberId);
        if (role != null) {
            members.addAll(sseRegistry.membersWithRole(role));
        }
        return members;
    }

    // 상품 요약은 알림의 이름표일 뿐이라, 조회에 실패해도 알림은 만든다
    private String itemSummaryOf(Long sellerOrderId) {
        if (sellerOrderId == null) {
            return null;
        }
        try {
            ShipmentResult shipment = shipmentQuery.findBySellerOrderId(sellerOrderId);
            return shipment == null ? null : shipment.itemSummary();
        } catch (RuntimeException e) {
            log.warn("알림 상품 요약 조회 실패: sellerOrderId={}", sellerOrderId, e);
            return null;
        }
    }

    // SSE로는 "새 알림 왔음" 신호(안 읽은 수)만 보낸다. 알림 원본은 DB에 있어 연결이 끊겨도 다시 조회하면 맞춰진다.
    // 커밋 후에 보내야 방금 저장·변경한 내용이 개수에 반영된다.
    private void pushUnreadCountAfterCommit(Long memberId) {
        pushUnreadCountAfterCommit(Set.of(memberId));
    }

    private void pushUnreadCountAfterCommit(Set<Long> memberIds) {
        List<Long> connected = memberIds.stream().filter(sseRegistry::hasConnection).toList();
        if (connected.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (Long memberId : connected) {
                    try {
                        // 숫자는 회원마다 다름 (관리자는 역할 알림 포함): 연결할 때 기억한 역할로 센다
                        sseRegistry.sendUnreadCount(memberId,
                                notificationRepository.countVisibleUnread(memberId, sseRegistry.roleOf(memberId)));
                    } catch (RuntimeException e) {
                        // 실시간 신호가 실패해도 알림은 저장돼 있으므로 다음 조회 때 보인다
                        log.warn("SSE 알림 수 전송 실패: memberId={}", memberId, e);
                    }
                }
            }
        });
    }
}
