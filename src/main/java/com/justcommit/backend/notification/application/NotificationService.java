package com.justcommit.backend.notification.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.notification.domain.Notification;
import com.justcommit.backend.notification.domain.NotificationErrorCode;
import com.justcommit.backend.notification.domain.NotificationType;
import com.justcommit.backend.notification.infrastructure.repository.NotificationRepository;
import com.justcommit.backend.notification.presentation.dto.NotificationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final NotificationRepository notificationRepository;

    // 이벤트로 알림을 만든다. 같은 이벤트가 다시 오면(Kafka 중복 전달) 새로 만들지 않는다.
    @Transactional
    public void create(Long memberId, NotificationType type, Long sellerOrderId, String eventKey) {
        if (memberId == null) {
            // 받는 사람을 모르는 이벤트 (예: 구매자 id를 저장하기 전에 만든 배송). 다시 받아도 같으므로 건너뛴다.
            log.warn("받는 사람이 없어 알림을 만들지 않음: type={}, eventKey={}", type, eventKey);
            return;
        }
        if (notificationRepository.existsByEventKey(eventKey)) {
            log.info("이미 만든 알림 (중복 이벤트): eventKey={}", eventKey);
            return;
        }
        notificationRepository.save(new Notification(memberId, type, sellerOrderId, eventKey));
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
        notification.read(LocalDateTime.now(SEOUL));
        return NotificationResponse.from(notification);
    }

    @Transactional
    public void readAll(Long memberId) {
        notificationRepository.readAll(memberId, LocalDateTime.now(SEOUL));
    }
}
