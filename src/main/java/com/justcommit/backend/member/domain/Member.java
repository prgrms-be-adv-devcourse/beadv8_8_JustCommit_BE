package com.justcommit.backend.member.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 회원 (id, created_at, updated_at은 BaseTimeEntity에서 상속)
@Entity
@Table(
        name = "member",
        uniqueConstraints = {
                // 제약 이름을 직접 지정 -> 중복 저장 시 어떤 값이 겹쳤는지 구분 가능
                @UniqueConstraint(name = "uk_member_email", columnNames = "email"),
                @UniqueConstraint(name = "uk_member_nickname", columnNames = "nickname"),
                @UniqueConstraint(name = "uk_member_phone", columnNames = "phone"),
                @UniqueConstraint(name = "uk_member_provider", columnNames = {"provider", "provider_id"})
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity  {
        @Column(length = 100)
        private String email; // 소셜 회원은 NULL 가능

        @Column(length = 100)
        private String password; // BCrypt 해시(60자), 소셜 회원은 NULL

        @Column(nullable = false, length = 20)
        private String nickname;

        @Column(nullable = false, length = 20)
        private String phone; // 숫자만 저장

        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Role role;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Provider provider;

        @Column(name = "provider_id", length = 100)
        private String providerId; // 소셜 회원 식별값, 로컬 회원은 NULL

        // 계좌 (가입 시 필수 — 후속 이슈에서 가입 API에 추가하면서 NOT NULL로 변경 예정)
        @Column(length = 10)
        private String bankCode;

        @Column(length = 255)
        private String accountNoEnc; // AES 암호화한 계좌번호

        @Column(length = 50)
        private String accountHolder;

        private LocalDateTime accountUpdatedAt;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private MemberStatus status;

        // 이메일·비밀번호 회원가입 (권한 MEMBER, 상태 ACTIVE로 고정)
        public static Member createLocal(String email, String encodedPassword, String nickname, String phone) {
                Member member = new Member();
                member.email = email;
                member.password = encodedPassword;
                member.nickname = nickname;
                member.phone = phone;
                member.role = Role.MEMBER;
                member.provider = Provider.LOCAL;
                member.status = MemberStatus.ACTIVE;
                return member;
        }
}
