package com.justcommit.backend.member.auth.infrastructure.oauth2;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;

import java.util.Map;

// 카카오 사용자 정보 응답(/v2/user/me)에서 회원번호와 인증된 이메일만 꺼냄
public record KakaoUserInfo(String providerId, String email) {

  private static final String ID = "id";
  private static final String KAKAO_ACCOUNT = "kakao_account";
  private static final String EMAIL = "email";
  private static final String IS_EMAIL_VALID = "is_email_valid";
  private static final String IS_EMAIL_VERIFIED = "is_email_verified";

  public static KakaoUserInfo from(Map<String, Object> attributes) {
    // 카카오 명세는 Long이지만, Map으로 받으면 Jackson이 값 크기에 따라 Integer 또는 Long으로 변환함
    // providerId 컬럼이 문자열이라 어느 쪽이든 문자열로 통일
    Object id = attributes.get(ID);
    if (id == null) {
      throw new BusinessException(MemberErrorCode.SOCIAL_LOGIN_FAILED);
    }
    return new KakaoUserInfo(String.valueOf(id), verifiedEmail(attributes));
  }

  // 유효하고 인증된 이메일만 사용
  private static String verifiedEmail(Map<String, Object> attributes) {
    if (!(attributes.get(KAKAO_ACCOUNT) instanceof Map<?, ?> account)) {
      throw new BusinessException(MemberErrorCode.SOCIAL_EMAIL_REQUIRED);
    }

    boolean valid = Boolean.TRUE.equals(account.get(IS_EMAIL_VALID));
    boolean verified = Boolean.TRUE.equals(account.get(IS_EMAIL_VERIFIED));

    if (!(account.get(EMAIL) instanceof String email) || email.isBlank() || !valid || !verified) {
      throw new BusinessException(MemberErrorCode.SOCIAL_EMAIL_REQUIRED);
    }
    return email;
  }
}
