package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.JwtProvider;
import com.justcommit.backend.common.security.SignupClaims;
import com.justcommit.backend.common.security.TokenException;
import com.justcommit.backend.member.MemberRegisteredEvent;
import com.justcommit.backend.member.domain.Address;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.repository.AddressRepository;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SocialSignupServiceTest {

  private static final String SIGNUP_TOKEN = "signup-token";

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private AddressRepository addressRepository;

  @Mock
  private AccountCipher accountCipher;

  @Mock
  private JwtProvider jwtProvider;

  @Mock
  private RefreshTokenIssuer refreshTokenIssuer;

  @Mock
  private ApplicationEventPublisher eventPublisher;

  @InjectMocks
  private SocialSignupService socialSignupService;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(socialSignupService, "accessTokenExpiration", Duration.ofMinutes(30));
  }

  // 테스트용 추가정보
  private static SocialSignupCommand command() {
    return new SocialSignupCommand("소셜회원", "01012345678",
            "004", "12345678901", "홍길동",
            "홍길동", "01012345678", "12345", "서울시 테스트로 1", null, null);
  }

  private void givenValidToken() {
    given(jwtProvider.parseSignupToken(SIGNUP_TOKEN))
            .willReturn(new SignupClaims("KAKAO", "123", " User@Kakao.com "));
  }

  private static void assertErrorCode(Runnable call, MemberErrorCode expected) {
    assertThatThrownBy(call::run)
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(expected);
  }

  @Test
  @DisplayName("가입 토큰의 정보로 소셜 회원·기본 배송지를 저장하고 바로 토큰 발급")
  void signup() {
    givenValidToken();
    given(memberRepository.findByProviderAndProviderId(Provider.KAKAO, "123")).willReturn(Optional.empty());
    given(memberRepository.existsByEmail("user@kakao.com")).willReturn(false);
    given(memberRepository.existsByNickname("소셜회원")).willReturn(false);
    given(memberRepository.existsByPhone("01012345678")).willReturn(false);
    given(accountCipher.encrypt("12345678901")).willReturn("v1:encrypted");

    given(memberRepository.saveAndFlush(any(Member.class))).willAnswer(invocation -> {
      Member saved = invocation.getArgument(0);
      ReflectionTestUtils.setField(saved, "id", 5L);
      return saved;
    });
    given(jwtProvider.createAccessToken(5L, "MEMBER")).willReturn("access-token");
    given(refreshTokenIssuer.issue(5L)).willReturn("refresh-token");

    LoginResult result = socialSignupService.signup(SIGNUP_TOKEN, command());

    // 응답: 가입 후 바로 로그인
    assertThat(result.accessToken()).isEqualTo("access-token");
    assertThat(result.refreshToken()).isEqualTo("refresh-token");
    assertThat(result.nickname()).isEqualTo("소셜회원");
    assertThat(result.role()).isEqualTo("MEMBER");

    // 저장된 회원: 이메일·회원번호는 토큰에서, 비밀번호 없음, 계좌번호는 암호문
    ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
    verify(memberRepository).saveAndFlush(captor.capture());
    Member saved = captor.getValue();
    assertThat(saved.getProvider()).isEqualTo(Provider.KAKAO);
    assertThat(saved.getProviderId()).isEqualTo("123");
    assertThat(saved.getEmail()).isEqualTo("user@kakao.com");
    assertThat(saved.getPassword()).isNull();
    assertThat(saved.getAccountNoEnc()).isEqualTo("v1:encrypted");

    verify(addressRepository).save(any(Address.class));

    // 회원 생성 이벤트 발행 (systemAccount=false)
    verify(eventPublisher).publishEvent(new MemberRegisteredEvent(5L, false));
  }

  @Test
  @DisplayName("가입 토큰이 없으면 INVALID_SIGNUP_TOKEN")
  void noToken() {
    assertErrorCode(() -> socialSignupService.signup(null, command()), MemberErrorCode.INVALID_SIGNUP_TOKEN);
    assertErrorCode(() -> socialSignupService.signup(" ", command()), MemberErrorCode.INVALID_SIGNUP_TOKEN);
    verifyNoInteractions(memberRepository);
  }

  @Test
  @DisplayName("만료·위조·Access 토큰(TokenException)이면 INVALID_SIGNUP_TOKEN")
  void invalidToken() {
    given(jwtProvider.parseSignupToken("bad-token"))
            .willThrow(new TokenException(TokenException.Reason.INVALID));

    assertErrorCode(() -> socialSignupService.signup("bad-token", command()), MemberErrorCode.INVALID_SIGNUP_TOKEN);
    verifyNoInteractions(memberRepository);
  }

  @Test
  @DisplayName("이미 가입한 소셜 계정이면 DUPLICATE_SOCIAL_ACCOUNT")
  void alreadySignedUp() {
    givenValidToken();
    given(memberRepository.findByProviderAndProviderId(Provider.KAKAO, "123"))
            .willReturn(Optional.of(Member.createSocial(Provider.KAKAO, "123", "user@kakao.com",
                    "소셜회원", "01012345678", "004", "v1:encrypted", "홍길동")));

    assertErrorCode(() -> socialSignupService.signup(SIGNUP_TOKEN, command()), MemberErrorCode.DUPLICATE_SOCIAL_ACCOUNT);
    verify(memberRepository, never()).saveAndFlush(any());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  @DisplayName("같은 이메일로 가입한 회원이 있으면 SOCIAL_EMAIL_CONFLICT")
  void emailConflict() {
    givenValidToken();
    given(memberRepository.findByProviderAndProviderId(Provider.KAKAO, "123")).willReturn(Optional.empty());
    given(memberRepository.existsByEmail("user@kakao.com")).willReturn(true);

    assertErrorCode(() -> socialSignupService.signup(SIGNUP_TOKEN, command()), MemberErrorCode.SOCIAL_EMAIL_CONFLICT);
    verify(memberRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("동시 요청으로 DB 유니크 제약에 걸리면 DUPLICATE_SOCIAL_ACCOUNT")
  void concurrentSignup() {
    givenValidToken();
    given(memberRepository.findByProviderAndProviderId(Provider.KAKAO, "123")).willReturn(Optional.empty());
    given(memberRepository.existsByEmail("user@kakao.com")).willReturn(false);
    given(memberRepository.existsByNickname("소셜회원")).willReturn(false);
    given(memberRepository.existsByPhone("01012345678")).willReturn(false);
    given(accountCipher.encrypt("12345678901")).willReturn("v1:encrypted");

    given(memberRepository.saveAndFlush(any(Member.class))).willThrow(new DataIntegrityViolationException(
            "could not execute statement",
            new RuntimeException("duplicate key value violates unique constraint \"uk_member_provider\"")));

    assertErrorCode(() -> socialSignupService.signup(SIGNUP_TOKEN, command()), MemberErrorCode.DUPLICATE_SOCIAL_ACCOUNT);
    verify(addressRepository, never()).save(any());
    verifyNoInteractions(eventPublisher);
  }
}
