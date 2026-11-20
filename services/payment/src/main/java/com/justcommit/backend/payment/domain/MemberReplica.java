package com.justcommit.backend.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * member.events.v1로 채우는 회원 복제본 (#387). 탈퇴 회원의 지갑 처리, 충전 거절은 #397, #396에서 이 값을 쓴다.
 * 쓰기는 저장소의 조건부 upsert로만 한다 (이벤트 시각 비교와 저장을 한 문장에서).
 */
@Entity
@Table(name = "member_replica")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberReplica {

    // member의 회원 ID 그대로
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
}
