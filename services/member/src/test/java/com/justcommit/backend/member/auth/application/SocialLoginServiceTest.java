package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.unlink.domain.KakaoUnlinkReceipt;
import com.justcommit.backend.member.unlink.infrastructure.repository.KakaoUnlinkReceiptRepository;
import com.justcommit.backend.member.withdrawal.application.RejoinRestriction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SocialLoginServiceTest {

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private MemberLocker memberLocker;

  @Mock
  private JwtProvider jwtProvider;

  @Mock
  private RefreshTokenIssuer refreshTokenIssuer;

  @Mock
  private AccessIssueTimeGuard accessIssueTimeGuard;

  @Mock
  private KakaoUnlinkReceiptRepository unlinkReceiptRepository;

  @Mock
  private RejoinRestriction rejoinRestriction;

  @InjectMocks
  private SocialLoginService socialLoginService;

  private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(socialLoginService, "accessTokenExpiration", Duration.ofMinutes(30));
    ReflectionTestUtils.setField(socialLoginService, "signupTokenExpiration", Duration.ofMinutes(10));
    // 가드는 넘겨받은 로그인 처리를 그대로 한 번 실행 (트랜잭션 · 대기는 AccessIssueTimeGuardIntegrationTest에서 확인)
    given(accessIssueTimeGuard.runWithRetry(any()))
        .willAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
  }

  // 테스트용 회원 (계좌 값은 createLocal이 요구해서 넣은 가짜 값)
  private static Member member(Long id) {
    Member member = Member.createLocal("user@kakao.com", null, "닉네임", "01012345678",
            "004", "v1:encrypted", null, "홍길동");
    ReflectionTestUtils.setField(member, "id", id);
    return member;
  }

  @Test
  @DisplayName("기존 소셜 회원이면 Access·Refresh 발급")
  void existingMember() {
    given(memberLocker.lockByProvider(Provider.KAKAO, "123"))
            .willReturn(Optional.of(member(4L)));
    given(accessIssueTimeGuard.issuableNow(4L)).willReturn(NOW);
    given(jwtProvider.createAccessToken(4L, "MEMBER", NOW)).willReturn("access-token");
    given(refreshTokenIssuer.issue(4L, 0)).willReturn("refresh-token");

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
    // 기존 회원은 재가입 판정을 하지 않음 (#611은 신규 판정에만)
    verify(rejoinRestriction, never()).checkEmail(anyString());
    // ACTIVE 회원의 평소 로그인은 탈퇴 보류 안내 대상이 아니고 처리 기록도 찾지 않음 (#616)
    assertThat(((SocialLoginResult.ExistingMember) result).withdrawalHeldRestored()).isFalse();
    verify(unlinkReceiptRepository, never()).findByMemberIdAndUnlinkVersionAndHandlesWithdrawalTrue(anyLong(), anyInt());
  }

  @Test
  @DisplayName("탈퇴가 보류됐던 INACTIVE 회원이 다시 로그인하면 복구 표시, 무효화하기 전에 처리 기록을 확인 (#616)")
  void withdrawalHeldMemberRestored() {
    Member member = inactiveMember(4L, 1);
    given(memberLocker.lockByProvider(Provider.KAKAO, "123")).willReturn(Optional.of(member));
    given(accessIssueTimeGuard.issuableNow(4L)).willReturn(NOW);
    given(unlinkReceiptRepository.findByMemberIdAndUnlinkVersionAndHandlesWithdrawalTrue(4L, 1))
        .willReturn(Optional.of(heldReceipt(4L, 1)));

    SocialLoginResult result = socialLoginService.login(
            new SocialLoginCommand(Provider.KAKAO, "123", "user@kakao.com"));

    assertThat(((SocialLoginResult.ExistingMember) result).withdrawalHeldRestored()).isTrue();
    assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    InOrder order = inOrder(unlinkReceiptRepository);
    order.verify(unlinkReceiptRepository).findByMemberIdAndUnlinkVersionAndHandlesWithdrawalTrue(4L, 1);
    order.verify(unlinkReceiptRepository).invalidatePending(anyString(), any());
  }

  @Test
  @DisplayName("처리 기록이 없는 INACTIVE 회원(관리자 해제 등)은 복구만 하고 표시하지 않음 (#616)")
  void inactiveWithoutHeldRecordNotMarked() {
    Member member = inactiveMember(4L, 1);
    given(memberLocker.lockByProvider(Provider.KAKAO, "123")).willReturn(Optional.of(member));
    given(accessIssueTimeGuard.issuableNow(4L)).willReturn(NOW);
    given(unlinkReceiptRepository.findByMemberIdAndUnlinkVersionAndHandlesWithdrawalTrue(4L, 1))
        .willReturn(Optional.empty());

    SocialLoginResult result = socialLoginService.login(
            new SocialLoginCommand(Provider.KAKAO, "123", "user@kakao.com"));

    assertThat(((SocialLoginResult.ExistingMember) result).withdrawalHeldRestored()).isFalse();
    assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    verify(unlinkReceiptRepository).invalidatePending(anyString(), any());
  }

  private static Member inactiveMember(Long id, int tokenVersion) {
    Member member = member(id);
    ReflectionTestUtils.setField(member, "status", MemberStatus.INACTIVE);
    ReflectionTestUtils.setField(member, "tokenVersion", tokenVersion);
    return member;
  }

  // 자동 탈퇴 판단에서 BLOCKED로 정해져 안내 메일 대기 중인 처리 기록
  private static KakaoUnlinkReceipt heldReceipt(Long memberId, int unlinkVersion) {
    KakaoUnlinkReceipt receipt = KakaoUnlinkReceipt.received("123", "UNLINK_FROM_APPS", NOW);
    receipt.markInactivated(memberId, unlinkVersion, NOW);
    receipt.markHandlesWithdrawal();
    receipt.markNoticePending(NOW);
    return receipt;
  }

  @Test
  @DisplayName("탈퇴 회원이면 SOCIAL_LOGIN_FAILED, Access · Refresh · 가입 토큰 발급 없음")
  void deletedMember() {
    Member member = member(4L);
    ReflectionTestUtils.setField(member, "status", MemberStatus.DELETED);
    given(memberLocker.lockByProvider(Provider.KAKAO, "123"))
            .willReturn(Optional.of(member));

    assertThatThrownBy(() -> socialLoginService.login(
            new SocialLoginCommand(Provider.KAKAO, "123", "user@kakao.com")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.SOCIAL_LOGIN_FAILED);
    verify(jwtProvider, never()).createAccessToken(anyLong(), anyString(), any());
    verify(accessIssueTimeGuard, never()).issuableNow(anyLong());
    verify(jwtProvider, never()).createSignupToken(anyString(), anyString(), anyString());
    verify(refreshTokenIssuer, never()).issue(anyLong(), anyInt());
  }

  @Test
  @DisplayName("발급 시각 확인이 중단되면 연결 해제 기록 무효화 · ACTIVE 복구 · 토큰 발급을 하지 않음 (#546)")
  void issueTimeCheckComesBeforeStateChanges() {
    Member member = member(4L);
    ReflectionTestUtils.setField(member, "status", MemberStatus.INACTIVE);
    given(memberLocker.lockByProvider(Provider.KAKAO, "123"))
            .willReturn(Optional.of(member));
    given(accessIssueTimeGuard.issuableNow(4L)).willThrow(new IllegalStateException("아직 발급할 수 없음"));

    assertThatThrownBy(() -> socialLoginService.login(
            new SocialLoginCommand(Provider.KAKAO, "123", "user@kakao.com")))
            .isInstanceOf(IllegalStateException.class);

    verify(unlinkReceiptRepository, never()).invalidatePending(anyString(), any());
    assertThat(member.getStatus()).isEqualTo(MemberStatus.INACTIVE);
    verify(jwtProvider, never()).createAccessToken(anyLong(), anyString(), any());
    verify(refreshTokenIssuer, never()).issue(anyLong(), anyInt());
  }

  @Test
  @DisplayName("신규 회원이면 정규화한 이메일로 가입 토큰 발급")
  void newMember() {
    given(memberLocker.lockByProvider(Provider.KAKAO, "123"))
            .willReturn(Optional.empty());
    given(memberRepository.existsByEmail("user@kakao.com")).willReturn(false);
    given(jwtProvider.createSignupToken("KAKAO", "123", "user@kakao.com")).willReturn("signup-token");

    SocialLoginResult result = socialLoginService.login(
            new SocialLoginCommand(Provider.KAKAO, "123", " User@Kakao.com "));

    assertThat(result).isInstanceOfSatisfying(SocialLoginResult.NewMember.class, newMember -> {
      assertThat(newMember.signupToken()).isEqualTo("signup-token");
      assertThat(newMember.expiresIn()).isEqualTo(600);
    });
    verify(refreshTokenIssuer, never()).issue(anyLong(), anyInt());
    verify(rejoinRestriction).checkEmail("user@kakao.com");
  }

  @Test
  @DisplayName("신규 회원이 30일 안에 탈퇴한 이메일이면 REJOIN_RESTRICTED_EMAIL, 가입 토큰 · Access · Refresh 발급 없음 (#611)")
  void newMemberRejoinRestricted() {
    given(memberLocker.lockByProvider(Provider.KAKAO, "123"))
            .willReturn(Optional.empty());
    given(memberRepository.existsByEmail("user@kakao.com")).willReturn(false);
    willThrow(new BusinessException(MemberErrorCode.REJOIN_RESTRICTED_EMAIL))
            .given(rejoinRestriction).checkEmail("user@kakao.com");

    assertThatThrownBy(() -> socialLoginService.login(
            new SocialLoginCommand(Provider.KAKAO, "123", "User@Kakao.com")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.REJOIN_RESTRICTED_EMAIL);
    verify(jwtProvider, never()).createSignupToken(anyString(), anyString(), anyString());
    verify(jwtProvider, never()).createAccessToken(anyLong(), anyString(), any());
    verify(refreshTokenIssuer, never()).issue(anyLong(), anyInt());
  }

  @Test
  @DisplayName("같은 이메일로 가입한 회원이 있으면 SOCIAL_EMAIL_CONFLICT")
  void emailConflict() {
    given(memberLocker.lockByProvider(Provider.KAKAO, "123"))
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
