package com.justcommit.backend.product.member.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

// 회원 복제본. 행은 저장소의 upsert 쿼리로만 만들고 바꾼다 (이벤트 순서 · 중복을 쿼리 조건으로 막음)
@Entity
@Table(name = "member_replica")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberReplica {

    // 탈퇴한 회원은 닉네임 대신 이 이름으로 보여 준다 (docs/glossary.md 「탈퇴」)
    public static final String WITHDRAWN_NICKNAME = "탈퇴한 회원";

    @Id
    @Column(name = "member_id")
    private Long memberId;

    @Column(length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MemberRole role;

    @Column(nullable = false)
    private boolean seller;

    @Column(nullable = false)
    private boolean withdrawn;

    @Column(name = "last_event_at", nullable = false)
    private OffsetDateTime lastEventAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public boolean isSeller() {
        return !withdrawn && seller;
    }

    public String displayNickname() {
        return withdrawn ? WITHDRAWN_NICKNAME : nickname;
    }
}
