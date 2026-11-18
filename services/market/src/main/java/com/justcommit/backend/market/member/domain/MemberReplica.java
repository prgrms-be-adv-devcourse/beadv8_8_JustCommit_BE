package com.justcommit.backend.market.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * member.events.v1로 채우는 회원 복제본 (#275). member DB를 직접 보지 않고 이걸로 닉네임 · 탈퇴 여부를 확인한다.
 * 쓰기는 저장소의 조건부 upsert로만 한다 (이벤트 시각 비교와 저장을 한 문장에서).
 */
@Entity
@Table(name = "member_replica")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberReplica {

  // member의 회원 ID 그대로 (market이 번호를 만들지 않음)
  @Id
  @Column(name = "member_id")
  private Long memberId;

  // 탈퇴 이벤트가 먼저 오면 비어 있을 수 있음
  @Column(length = 20)
  private String nickname;

  @Column(length = 20)
  private String role;

  @Column(nullable = false)
  private boolean seller;

  @Column(nullable = false)
  private boolean withdrawn;

  // 마지막으로 반영한 이벤트의 occurredAt
  @Column(name = "last_event_at", nullable = false)
  private OffsetDateTime lastEventAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  // 판매자 배송지 목록 암호문 그대로 (v1.k1:...). 쓸 때만 복호화한다.
  // 탈퇴 이벤트 반영 후에는 비어 있고, 늦게 도착한 주소 이벤트로 다시 채워지지 않는다
  @Column(name = "seller_addresses_enc", columnDefinition = "text")
  private String sellerAddressesEnc;

  // 마지막으로 반영한 주소 이벤트의 occurredAt (회원 이벤트 시각 last_event_at과 따로 비교)
  @Column(name = "seller_addresses_event_at")
  private OffsetDateTime sellerAddressesEventAt;
}
