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

        // 계좌 (구매자 예치금 충전·환급, 판매자 정산에 공통 사용) — 가입 시 필수
        @Column(name = "bank_code", nullable = false, length = 10)
        private String bankCode;

        @Column(name = "account_no_enc", nullable = false, length = 255)
        private String accountNoEnc; // AES-256-GCM 암호문 ("v1:" + Base64)

        @Column(name = "account_holder", nullable = false, length = 50) // 예금주
        private String accountHolder;

        @Column(name = "account_updated_at", nullable = false)
        private LocalDateTime accountUpdatedAt;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private MemberStatus status;

        // 이메일·비밀번호 회원가입 (권한 MEMBER, 상태 ACTIVE로 고정)
        public static Member createLocal(String email, String encodedPassword, String nickname, String phone,
                                         String bankCode, String encryptedAccountNo, String accountHolder) {
                Member member = new Member();
                member.email = email;
                member.password = encodedPassword;
                member.nickname = nickname;
                member.phone = phone;
                member.role = Role.MEMBER;
                member.provider = Provider.LOCAL;
                member.status = MemberStatus.ACTIVE;
                member.bankCode = bankCode;
                member.accountNoEnc = encryptedAccountNo;
                member.accountHolder = accountHolder;
                member.accountUpdatedAt = LocalDateTime.now();
                return member;
        }

        // 판매자 등록 시 권한 변경
        public void promoteToSeller() {
                if (this.role == Role.MEMBER) {
                        this.role = Role.SELLER;
                }
        }
}
