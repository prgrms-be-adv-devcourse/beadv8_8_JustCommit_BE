package com.justcommit.backend.member.application;

// 소셜 회원가입 추가정보 (presentation의 SocialSignupRequest → application)
// 이메일·가입 경로·회원번호는 가입 토큰에서 꺼내므로 여기에 없음
public record SocialSignupCommand(
        String nickname, String phone,
        String bankCode, String accountNo, String accountHolder,
        String recipientName, String recipientPhone, String zipcode,
        String address1, String address2, String addressName
) {
}
