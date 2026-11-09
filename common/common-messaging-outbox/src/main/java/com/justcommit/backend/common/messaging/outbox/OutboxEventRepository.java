package com.justcommit.backend.common.messaging.outbox;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Outbox 모듈 안에서만 쓰는 저장소. 서비스는 OutboxPublisher로만 발행한다
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  // 재전송 후보: 잠그지 않고 id만 읽음. 잠금은 한 행씩 findPendingForUpdateSkipLocked로
  @Query("select e.id from OutboxEvent e "
      + "where e.status = com.justcommit.backend.common.messaging.outbox.OutboxStatus.PENDING "
      + "and e.createdAt < :before order by e.createdAt")
  List<UUID> findRetryIds(@Param("before") Instant before, Limit limit);

  // 다른 파드가 잡고 있거나 그사이 처리된 행은 기다리지 않고 빈 값 (정책 1.4-1)
  @Query(value = "select * from outbox_event where id = :id and status = 'PENDING' for update skip locked",
      nativeQuery = true)
  Optional<OutboxEvent> findPendingForUpdateSkipLocked(@Param("id") UUID id);

  // PENDING일 때만 바꿈: 이미 다른 쪽이 처리했으면 0을 돌려주고 아무것도 안 함
  @Modifying
  @Query("update OutboxEvent e "
      + "set e.status = com.justcommit.backend.common.messaging.outbox.OutboxStatus.PUBLISHED, "
      + "e.publishedAt = :now "
      + "where e.id = :id and e.status = com.justcommit.backend.common.messaging.outbox.OutboxStatus.PENDING")
  int markPublished(@Param("id") UUID id, @Param("now") Instant now);

  // 실패 횟수 +1, maxAttempts번째 실패면 FAILED (PENDING일 때만)
  @Modifying
  @Query("update OutboxEvent e "
      + "set e.attemptCount = e.attemptCount + 1, "
      + "e.lastError = :error, "
      + "e.status = case when e.attemptCount + 1 >= :maxAttempts "
      + "then com.justcommit.backend.common.messaging.outbox.OutboxStatus.FAILED "
      + "else com.justcommit.backend.common.messaging.outbox.OutboxStatus.PENDING end "
      + "where e.id = :id and e.status = com.justcommit.backend.common.messaging.outbox.OutboxStatus.PENDING")
  int recordFailure(@Param("id") UUID id, @Param("error") String error, @Param("maxAttempts") int maxAttempts);

  // 발행이 끝난 지 오래된 행 정리 (7일, docs/conventions/events.md)
  @Modifying
  @Query("delete from OutboxEvent e "
      + "where e.status = com.justcommit.backend.common.messaging.outbox.OutboxStatus.PUBLISHED "
      + "and e.publishedAt < :before")
  int deletePublishedBefore(@Param("before") Instant before);
}
