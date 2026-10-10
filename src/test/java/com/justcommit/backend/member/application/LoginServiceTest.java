package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.JwtProvider;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.redis.RefreshTokenRedisStore;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private PasswordEncoder passwordEncoder;

  @Mock
  private JwtProvider jwtProvider;

  @Mock
  private RefreshTokenRedisStore refreshTokenStore;

  @InjectMocks
  private LoginService loginService;

  private static final String EMAIL = "test@gmail.com";
  private static final String PASSWORD = "plant1234";
  private static final String ENCODED_PASSWORD = "encoded-password";
  private static final Long MEMBER_ID = 1L;

  // @Value 필드는 Spring없이 채워지지 않아서 직접 주입
  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(loginService, "accessTokenExpiration", Duration.ofMinutes(30));
  }

  private Member member() {
    Member member = Member.createLocal(EMAIL, ENCODED_PASSWORD, "떡볶이", "01012345678",
            "004", "v1:encrypted-account", "홍길동");
    ReflectionTestUtils.setField(member, "id", MEMBER_ID);
    return member;
  }

  private void givenValidLogin() {
    given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member()));
    given(passwordEncoder.matches(PASSWORD, ENCODED_PASSWORD)).willReturn(true);
    given(jwtProvider.createAccessToken(MEMBER_ID, "MEMBER")).willReturn("access-token");
  }

  @Test
  @DisplayName("로그인 성공: 정규화한 이메일로 조회하고 Access토큰과 Refresh토큰(Redis 저장)을 발급")
  void login_success() {
    givenValidLogin();

    // when: 앞뒤 공백·대문자가 섞인 이메일로 요청
    LoginResult result = loginService.login(new LoginCommand("  Test@Gmail.com ", PASSWORD));

    // then: 응답 값
    assertThat(result.accessToken()).isEqualTo("access-token");
    assertThat(result.expiresIn()).isEqualTo(1800);
    assertThat(result.nickname()).isEqualTo("떡볶이");
    assertThat(result.role()).isEqualTo("MEMBER");
    assertThat(result.refreshTokenTtl()).isEqualTo(Duration.ofHours(3));

    // then: 응답으로 내려준 Refresh 토큰을 그대로 회원 id·3시간으로 Redis에 저장
    ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
    then(refreshTokenStore).should().save(tokenCaptor.capture(), eq(MEMBER_ID), eq(Duration.ofHours(3)));
    assertThat(tokenCaptor.getValue()).isEqualTo(result.refreshToken());

    // then: 32바이트 랜덤 → URL 안전 Base64(패딩 없음) 43자
    assertThat(result.refreshToken()).matches("^[A-Za-z0-9_-]{43}$");
  }

  @Test
  @DisplayName("로그인할 때마다 서로 다른 Refresh토큰을 발급")
  void login_issuesDifferentRefreshTokens() {
    givenValidLogin();

    LoginResult first = loginService.login(new LoginCommand(EMAIL, PASSWORD));
    LoginResult second = loginService.login(new LoginCommand(EMAIL, PASSWORD));

    assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
  }

  @Test
  @DisplayName("없는 이메일이면 LOGIN_FAILED, 토큰을 발급하지 않음")
  void login_emailNotFound() {
    given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.empty());

    assertThatThrownBy(() -> loginService.login(new LoginCommand(EMAIL, PASSWORD)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.LOGIN_FAILED);

    then(jwtProvider).shouldHaveNoInteractions();
    then(refreshTokenStore).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("비밀번호가 틀리면 없는 이메일과 같은 LOGIN_FAILED, 토큰을 발급하지 않음")
  void login_wrongPassword() {
    given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member()));
    given(passwordEncoder.matches("wrong1234", ENCODED_PASSWORD)).willReturn(false);

    assertThatThrownBy(() -> loginService.login(new LoginCommand(EMAIL, "wrong1234")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.LOGIN_FAILED);

    then(jwtProvider).should(never()).createAccessToken(anyLong(), anyString());
    then(refreshTokenStore).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("LoginResult를 문자열로 출력해도 토큰 원문은 보이지 않음")
  void loginResult_toStringHidesTokens() {
    LoginResult result = new LoginResult("access-token", 1800, "떡볶이", "MEMBER",
            "refresh-token", Duration.ofHours(3));

    assertThat(result.toString())
            .doesNotContain("access-token")
            .doesNotContain("refresh-token");
  }
}
