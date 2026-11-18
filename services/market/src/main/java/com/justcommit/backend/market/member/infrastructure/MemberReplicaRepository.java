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
  // 탈퇴는 마지막 상태라 같은 시각의 이벤트보다 우선한다 (<=)
  // 같은 문장에서 판매자 배송지 암호문도 비운다 (탈퇴 이벤트 반영 후에는 주소 암호문이 남지 않음)
  @Modifying
  @Query(value = """
      insert into member_replica (member_id, withdrawn, last_event_at, updated_at)
      values (:memberId, true, :occurredAt, now())
      on conflict (member_id) do update
          set withdrawn = true,
              seller_addresses_enc = null,
              last_event_at = excluded.last_event_at,
              updated_at = now()
          where member_replica.last_event_at <= excluded.last_event_at
      """, nativeQuery = true)
  int markWithdrawn(@Param("memberId") Long memberId, @Param("occurredAt") OffsetDateTime occurredAt);

  // 판매자 배송지 목록: 없으면 주소만 채운 행을 만들고, 있으면 저장된 주소 이벤트 시각보다 새로운 경우에만 반영한다.
  // 같은 시각의 이벤트는 무시한다. 회원 칸(닉네임 · 권한 · 판매자 · 탈퇴)은 건드리지 않는다.
  // 행을 새로 만들 때 last_event_at은 1970-01-01(to_timestamp(0))로 둔다: 회원 이벤트 시각은 항상 이보다 뒤라,
  // 나중에 온 회원 · 탈퇴 이벤트가 정상으로 반영된다.
  // 탈퇴한 행은 갱신하지 않는다: 탈퇴 이벤트 반영 후에는 늦게 도착한 주소 이벤트로 다시 저장되지 않는다.
  @Modifying
  @Query(value = """
      insert into member_replica (member_id, withdrawn, last_event_at, updated_at,
                                  seller_addresses_enc, seller_addresses_event_at)
      values (:memberId, false, to_timestamp(0), now(), :encryptedAddresses, :occurredAt)
      on conflict (member_id) do update
          set seller_addresses_enc = excluded.seller_addresses_enc,
              seller_addresses_event_at = excluded.seller_addresses_event_at,
              updated_at = now()
          where not member_replica.withdrawn
            and (member_replica.seller_addresses_event_at is null
                  or member_replica.seller_addresses_event_at < excluded.seller_addresses_event_at)
      """, nativeQuery = true)
  int upsertSellerAddresses(@Param("memberId") Long memberId,
                            @Param("encryptedAddresses") String encryptedAddresses,
                            @Param("occurredAt") OffsetDateTime occurredAt);

}
