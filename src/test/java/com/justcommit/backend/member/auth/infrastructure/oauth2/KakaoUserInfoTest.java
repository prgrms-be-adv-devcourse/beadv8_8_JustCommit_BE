package com.justcommit.backend.member.auth.infrastructure.oauth2;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoUserInfoTest {

  // 정상 카카오 응답 (테스트마다 일부 값을 바꿔서 사용)
  private static Map<String, Object> account(Object email, Object valid, Object verified) {
    Map<String, Object> account = new HashMap<>();
    account.put("email", email);
    account.put("is_email_valid", valid);
    account.put("is_email_verified", verified);
    return account;
  }

  private static Map<String, Object> attributes(Object id, Map<String, Object> account) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("id", id);
    attributes.put("kakao_account", account);
    return attributes;
  }

  private static void assertErrorCode(Map<String, Object> attributes, MemberErrorCode expected) {
    assertThatThrownBy(() -> KakaoUserInfo.from(attributes))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(expected);
  }

  @Test
  @DisplayName("회원번호와 인증된 이메일을 꺼냄")
  void parse() {
    KakaoUserInfo info = KakaoUserInfo.from(
            attributes(1234567890123L, account("user@kakao.com", true, true)));

    assertThat(info.providerId()).isEqualTo("1234567890123");
    assertThat(info.email()).isEqualTo("user@kakao.com");
  }

  @Test
  @DisplayName("회원번호가 Integer로 들어와도 문자열로 변환")
  void integerId() {
    KakaoUserInfo info = KakaoUserInfo.from(
            attributes(12345, account("user@kakao.com", true, true)));

    assertThat(info.providerId()).isEqualTo("12345");
  }

  @Test
  @DisplayName("회원번호가 없으면 SOCIAL_LOGIN_FAILED")
  void noId() {
    assertErrorCode(attributes(null, account("user@kakao.com", true, true)),
            MemberErrorCode.SOCIAL_LOGIN_FAILED);
  }

  @Test
  @DisplayName("kakao_account가 없으면 SOCIAL_EMAIL_REQUIRED")
  void noAccount() {
    assertErrorCode(attributes(123L, null), MemberErrorCode.SOCIAL_EMAIL_REQUIRED);
  }

  @Test
  @DisplayName("이메일이 없거나 비어 있으면 SOCIAL_EMAIL_REQUIRED")
  void noEmail() {
    assertErrorCode(attributes(123L, account(null, true, true)), MemberErrorCode.SOCIAL_EMAIL_REQUIRED);
    assertErrorCode(attributes(123L, account(" ", true, true)), MemberErrorCode.SOCIAL_EMAIL_REQUIRED);
  }

  @Test
  @DisplayName("유효하지 않은 이메일이면 SOCIAL_EMAIL_REQUIRED")
  void invalidEmail() {
    assertErrorCode(attributes(123L, account("user@kakao.com", false, true)),
            MemberErrorCode.SOCIAL_EMAIL_REQUIRED);
  }

  @Test
  @DisplayName("인증되지 않은 이메일이면 SOCIAL_EMAIL_REQUIRED")
  void unverifiedEmail() {
    assertErrorCode(attributes(123L, account("user@kakao.com", true, false)),
            MemberErrorCode.SOCIAL_EMAIL_REQUIRED);
    assertErrorCode(attributes(123L, account("user@kakao.com", true, null)),
            MemberErrorCode.SOCIAL_EMAIL_REQUIRED);
  }
}
