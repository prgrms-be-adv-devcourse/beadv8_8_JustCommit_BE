package com.justcommit.backend.product.member.infrastructure;

import com.justcommit.backend.product.member.domain.MemberReplica;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface MemberReplicaRepository extends JpaRepository<MemberReplica, Long> {

    // 가입 · 정보 변경: 없으면 만들고, 있으면 더 새 이벤트일 때만 덮어씀
    // 같은 이벤트가 다시 오거나(시각 같음) 오래된 이벤트가 늦게 오면 아무것도 바꾸지 않음. 탈퇴한 회원은 되살리지 않음
    @Modifying
    @Query(value = """
            INSERT INTO member_replica (member_id, nickname, role, seller, withdrawn, last_event_at, created_at, updated_at)
            VALUES (:memberId, :nickname, :role, :seller, false, :occurredAt, now(), now())
            ON CONFLICT (member_id) DO UPDATE
               SET nickname = EXCLUDED.nickname,
                   role = EXCLUDED.role,
                   seller = EXCLUDED.seller,
                   last_event_at = EXCLUDED.last_event_at,
                   updated_at = now()
             WHERE member_replica.withdrawn = false
               AND member_replica.last_event_at < EXCLUDED.last_event_at
            """, nativeQuery = true)
    int upsertProfile(@Param("memberId") Long memberId,
                      @Param("nickname") String nickname,
                      @Param("role") String role,
                      @Param("seller") boolean seller,
                      @Param("occurredAt") OffsetDateTime occurredAt);

    // 탈퇴: 순서와 상관없이 항상 탈퇴 상태로 (가입 이벤트보다 먼저 와도 행을 만들어 둠)
    @Modifying
    @Query(value = """
            INSERT INTO member_replica (member_id, withdrawn, last_event_at, created_at, updated_at)
            VALUES (:memberId, true, :occurredAt, now(), now())
            ON CONFLICT (member_id) DO UPDATE
               SET withdrawn = true,
                   last_event_at = GREATEST(member_replica.last_event_at, EXCLUDED.last_event_at),
                   updated_at = now()
            """, nativeQuery = true)
    void upsertWithdrawn(@Param("memberId") Long memberId, @Param("occurredAt") OffsetDateTime occurredAt);
}
