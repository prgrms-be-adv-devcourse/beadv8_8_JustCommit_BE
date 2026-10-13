package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.JwtProvider;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SocialLoginServiceTest {

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private JwtProvider jwtProvider;

  @Mock
  private RefreshTokenIssuer refreshTokenIssuer;

  @InjectMocks
  private SocialLoginService socialLoginService;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(socialLoginService, "accessTokenExpiration", Duration.ofMinutes(30));
    ReflectionTestUtils.setField(socialLoginService, "signupTokenExpiration", Duration.ofMinutes(10));
  }

  // 테스트용 회원 (계좌 값은 createLocal이 요구해서 넣은 가짜 값)
  private static Member member(Long id) {
    Member member = Member.createLocal("user@kakao.com", null, "닉네임", "01012345678",
            "004", "v1:encrypted", "홍길동");
    ReflectionTestUtils.setField(member, "id", id);
    return member;
  }

  @Test
  @DisplayName("기존 소셜 회원이면 Access·Refresh 발급")
  void existingMember() {
    given(memberRepository.findByProviderAndProviderId(Provider.KAKAO, "123"))
            .willReturn(Optional.of(member(4L)));
    given(jwtProvider.createAccessToken(4L, "MEMBER")).willReturn("access-token");
    given(refreshTokenIssuer.issue(4L)).willReturn("refresh-token");

    SocialLoginResult result = socialLoginService.login(
            new SocialLoginCommand(Provider.KAKAO, "123", "user@kakao.com"));

    assertThat(result).isInstanceOfSatisfying(SocialLoginResult.ExistingMember.class, existing -> {
      LoginResult login = existing.loginResult();
      assertThat(login.accessToken()).isEqualTo("access-token");
      assertThat(login.expiresIn()).isEqualTo(1800);
      assertThat(login.nickname()).isEqualTo("닉네임");
      assertThat(login.role()).isEqualTo("MEMBER");
      assertThat(login.refreshToken()).isEqualTo("refresh-token");
    });
    verify(jwtProvider, never()).createSignupToken(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("신규 회원이면 정규화한 이메일로 가입 토큰 발급")
  void newMember() {
    given(memberRepository.findByProviderAndProviderId(Provider.KAKAO, "123"))
            .willReturn(Optional.empty());
    given(memberRepository.existsByEmail("user@kakao.com")).willReturn(false);
    given(jwtProvider.createSignupToken("KAKAO", "123", "user@kakao.com")).willReturn("signup-token");

    SocialLoginResult result = socialLoginService.login(
            new SocialLoginCommand(Provider.KAKAO, "123", " User@Kakao.com "));

    assertThat(result).isInstanceOfSatisfying(SocialLoginResult.NewMember.class, newMember -> {
      assertThat(newMember.signupToken()).isEqualTo("signup-token");
      assertThat(newMember.expiresIn()).isEqualTo(600);
    });
    verify(refreshTokenIssuer, never()).issue(anyLong());
  }

  @Test
  @DisplayName("같은 이메일로 가입한 회원이 있으면 SOCIAL_EMAIL_CONFLICT")
  void emailConflict() {
    given(memberRepository.findByProviderAndProviderId(Provider.KAKAO, "123"))
            .willReturn(Optional.empty());
    given(memberRepository.existsByEmail("user@kakao.com")).willReturn(true);

    assertThatThrownBy(() -> socialLoginService.login(
            new SocialLoginCommand(Provider.KAKAO, "123", "user@kakao.com")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.SOCIAL_EMAIL_CONFLICT);
    verify(jwtProvider, never()).createSignupToken(anyString(), anyString(), anyString());
  }
}
