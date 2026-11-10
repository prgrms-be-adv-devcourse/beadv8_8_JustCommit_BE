package com.justcommit.backend.market.member.infrastructure;

import com.justcommit.backend.market.member.domain.MemberReplica;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface MemberReplicaRepository extends JpaRepository<MemberReplica, Long> {

  // 가입 · 정보 변경: 없으면 만들고 있으면 더 새로운 이벤트일 때만 덮어씀.
  // 탈퇴 표시는 건드리지 않음 (탈퇴한 회원이 정보 변경 이벤트로 되살아나지 않게)
  @Modifying
  @Query(value = """
      insert into member_replica (member_id, nickname, role, seller, withdrawn, last_event_at, updated_at)
      values (:memberId, :nickname, :role, :seller, false, :occurredAt, now())
      on conflict (member_id) do update
         set nickname = excluded.nickname,
             role = excluded.role,
             seller = excluded.seller,
             last_event_at = excluded.last_event_at,
             updated_at = now()
       where member_replica.last_event_at < excluded.last_event_at
      """, nativeQuery = true)
  int upsertMember(@Param("memberId") Long memberId, @Param("nickname") String nickname,
                   @Param("role") String role, @Param("seller") boolean seller,
                   @Param("occurredAt") OffsetDateTime occurredAt);

  // 탈퇴: 복제본이 없어도 탈퇴 표시 행을 만듦 (늦게 온 가입 이벤트가 되살리지 못하게)
  @Modifying
  @Query(value = """
      insert into member_replica (member_id, withdrawn, last_event_at, updated_at)
      values (:memberId, true, :occurredAt, now())
      on conflict (member_id) do update
         set withdrawn = true,
             last_event_at = excluded.last_event_at,
             updated_at = now()
       where member_replica.last_event_at < excluded.last_event_at
      """, nativeQuery = true)
  int markWithdrawn(@Param("memberId") Long memberId, @Param("occurredAt") OffsetDateTime occurredAt);
}
