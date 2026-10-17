package com.justcommit.backend.notification.infrastructure.repository;

import com.justcommit.backend.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByMemberId(Long memberId, Pageable pageable);

    Page<Notification> findByMemberIdAndIsRead(Long memberId, boolean isRead, Pageable pageable);

    long countByMemberIdAndIsReadFalse(Long memberId);

    Optional<Notification> findByIdAndMemberId(Long id, Long memberId);

    boolean existsByEventKey(String eventKey);

    // 알림을 하나씩 읽어 오지 않고 한 번의 UPDATE로 모두 읽음 처리한다
    @Modifying(clearAutomatically = true)
    @Query("update Notification n set n.isRead = true, n.readAt = :readAt "
            + "where n.memberId = :memberId and n.isRead = false")
    int readAll(@Param("memberId") Long memberId, @Param("readAt") LocalDateTime readAt);
}
