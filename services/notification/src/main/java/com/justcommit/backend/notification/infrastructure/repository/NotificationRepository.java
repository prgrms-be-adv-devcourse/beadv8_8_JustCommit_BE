package com.justcommit.backend.notification.infrastructure.repository;

import com.justcommit.backend.notification.domain.Notification;
import com.justcommit.backend.notification.domain.RecipientRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

// 「내 알림」 = 나에게 온 알림 + 내 역할이 받는 역할 알림(관리자면 ADMIN 알림). role이 null이면 나에게 온 알림만
// (recipient_role = null은 SQL에서 참이 아니라 역할 알림이 섞이지 않음)
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    String VISIBLE = "(n.memberId = :memberId or n.recipientRole = :role)";

    @Query("select n from Notification n where " + VISIBLE)
    Page<Notification> findVisible(@Param("memberId") Long memberId, @Param("role") RecipientRole role,
                                   Pageable pageable);

    @Query("select n from Notification n where " + VISIBLE + " and n.isRead = :isRead")
    Page<Notification> findVisibleByIsRead(@Param("memberId") Long memberId, @Param("role") RecipientRole role,
                                           @Param("isRead") boolean isRead, Pageable pageable);

    @Query("select count(n) from Notification n where " + VISIBLE + " and n.isRead = false")
    long countVisibleUnread(@Param("memberId") Long memberId, @Param("role") RecipientRole role);

    @Query("select n from Notification n where n.id = :id and " + VISIBLE)
    Optional<Notification> findVisibleById(@Param("id") Long id, @Param("memberId") Long memberId,
                                           @Param("role") RecipientRole role);

    boolean existsByEventKey(String eventKey);

    // 알림을 하나씩 읽어 오지 않고 한 번의 UPDATE로 모두 읽음 처리한다. 역할 알림은 그 역할 모두에게 읽음이 된다
    @Modifying(clearAutomatically = true)
    @Query("update Notification n set n.isRead = true, n.readAt = :readAt where " + VISIBLE + " and n.isRead = false")
    int readAll(@Param("memberId") Long memberId, @Param("role") RecipientRole role,
                @Param("readAt") LocalDateTime readAt);
}
