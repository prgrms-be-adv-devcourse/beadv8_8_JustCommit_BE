package com.justcommit.backend.notification.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.notification.domain.Notification;
import com.justcommit.backend.notification.domain.NotificationErrorCode;
import com.justcommit.backend.notification.domain.NotificationType;
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
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    // 시간이 지나면 연결을 닫고, 브라우저가 다시 연결한다
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

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

    // 이벤트로 알림을 만든다. 같은 이벤트가 다시 오면(Kafka 중복 전달) 새로 만들지 않는다.
    private void save(Long memberId, NotificationType type, Long orderId, Long sellerOrderId,
                        Supplier<String> itemSummary, String eventKey) {
        if (memberId == null) {
            // 받는 사람을 모르는 이벤트 (예: 구매자 id를 저장하기 전에 만든 배송). 다시 받아도 같으므로 건너뛴다.
            log.warn("받는 사람이 없어 알림을 만들지 않음: type={}, eventKey={}", type, eventKey);
            return;
        }
        if (notificationRepository.existsByEventKey(eventKey)) {
            log.info("이미 만든 알림 (중복 이벤트): eventKey={}", eventKey);
            return;
        }
        notificationRepository.save(new Notification(memberId, type, orderId, sellerOrderId, itemSummary.get(), eventKey));
        pushUnreadCountAfterCommit(memberId);
    }

    @Transactional(readOnly = true)
    public PagingResponse<NotificationResponse> list(Long memberId, Boolean isRead, Pageable pageable) {
        Page<Notification> page = (isRead == null)
                ? notificationRepository.findByMemberId(memberId, pageable)
                : notificationRepository.findByMemberIdAndIsRead(memberId, isRead, pageable);
        return PagingResponse.from(page.map(NotificationResponse::from));
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long memberId) {
        return notificationRepository.countByMemberIdAndIsReadFalse(memberId);
    }

    @Transactional
    public NotificationResponse read(Long memberId, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndMemberId(notificationId, memberId)
                .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        notification.read(LocalDateTime.now());
        pushUnreadCountAfterCommit(memberId); // 다른 탭의 숫자도 맞춘다
        return NotificationResponse.from(notification);
    }

    @Transactional
    public void readAll(Long memberId) {
        notificationRepository.readAll(memberId, LocalDateTime.now());
        pushUnreadCountAfterCommit(memberId);
    }

    // 실시간 구독. 연결하자마자 지금 안 읽은 알림 수를 한 번 보낸다.
    public SseEmitter subscribe(Long memberId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sseRegistry.add(memberId, emitter, unreadCount(memberId));
        return emitter;
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
        if (!sseRegistry.hasConnection(memberId)) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    sseRegistry.sendUnreadCount(memberId, notificationRepository.countByMemberIdAndIsReadFalse(memberId));
                } catch (RuntimeException e) {
                    // 실시간 신호가 실패해도 알림은 저장돼 있으므로 다음 조회 때 보인다
                    log.warn("SSE 알림 수 전송 실패: memberId={}", memberId, e);
                }
            }
        });
    }
}
