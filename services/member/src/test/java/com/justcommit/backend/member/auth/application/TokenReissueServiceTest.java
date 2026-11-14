package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenOwner;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Role;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

/**
 * 재발급 흐름(읽기 → 회원 행 잠금 → 꺼내며 삭제 → 상태 · 버전 확인 → 발급)을 모의 객체로 확인한다.
 * 호출 순서만 확인하며, 실제 잠금 획득과 폐기와의 경쟁은 실제 DB 테스트(#409 5단계)에서 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class TokenReissueServiceTest {

  @Mock
  private RefreshTokenRedisStore refreshTokenStore;

  @Mock
  private RefreshTokenIssuer refreshTokenIssuer;

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private JwtProvider jwtProvider;

  @Mock
  private DatabaseClock databaseClock;

  @InjectMocks
  private TokenReissueService tokenReissueService;

  private static final String REFRESH_TOKEN = "old-refresh-token";
  private static final Long MEMBER_ID = 1L;
  private static final RefreshTokenOwner OWNER_V0 = new RefreshTokenOwner(MEMBER_ID, 0);
  private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(tokenReissueService, "accessTokenExpiration", Duration.ofMinutes(30));
  }

  // 판매자 등록을 마친 회원 (DB의 현재 role이 토큰에 반영되는지 확인)
  private Member seller() {
    Member member = Member.createLocal("test@gmail.com", "encoded", "떡볶이", "01012345678",
        "004", "v1:encrypted-account", "홍길동");
    ReflectionTestUtils.setField(member, "id", MEMBER_ID);
    ReflectionTestUtils.setField(member, "role", Role.SELLER);
    return member;
  }

  // 읽기 → 잠금 조회까지 준비
  private void givenFoundAndLocked(Member member) {
    given(refreshTokenStore.findOwner(REFRESH_TOKEN)).willReturn(Optional.of(OWNER_V0));
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member));
  }

  private void assertInvalidRefreshToken() {
    assertThatThrownBy(() -> tokenReissueService.reissue(REFRESH_TOKEN))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN);
  }

  @Test
  @DisplayName("재발급 성공: 읽기 → 회원 행 잠금 → 꺼내며 삭제 순서, DB의 현재 role · 인증 버전으로 새 Access · Refresh 발급")
  void reissue_success() {
    givenFoundAndLocked(seller());
    given(refreshTokenStore.findAndDelete(REFRESH_TOKEN)).willReturn(Optional.of(OWNER_V0));
    given(databaseClock.now()).willReturn(NOW);
    given(jwtProvider.createAccessToken(MEMBER_ID, "SELLER", NOW)).willReturn("new-access-token");
    given(refreshTokenIssuer.issue(MEMBER_ID, 0)).willReturn("new-refresh-token");

    TokenReissueResult result = tokenReissueService.reissue(REFRESH_TOKEN);

    assertThat(result.accessToken()).isEqualTo("new-access-token");
    assertThat(result.expiresIn()).isEqualTo(1800);
    assertThat(result.refreshToken()).isEqualTo("new-refresh-token");
    assertThat(result.refreshTokenTtl()).isEqualTo(Duration.ofDays(1));

    // 호출 순서 (실제 잠금 획득은 실제 DB 테스트에서 확인)
    InOrder order = inOrder(refreshTokenStore, memberRepository, databaseClock);
    order.verify(refreshTokenStore).findOwner(REFRESH_TOKEN);
    order.verify(memberRepository).findByIdForUpdate(MEMBER_ID);
    order.verify(refreshTokenStore).findAndDelete(REFRESH_TOKEN);
    order.verify(databaseClock).now(); // 잠금 · 모든 확인이 끝난 뒤 시각을 읽음
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  @DisplayName("Refresh 쿠키가 없거나 비어 있으면 INVALID_REFRESH_TOKEN, Redis는 조회하지 않음")
  void reissue_noCookie(String refreshToken) {
    assertThatThrownBy(() -> tokenReissueService.reissue(refreshToken))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN);

    then(refreshTokenStore).shouldHaveNoInteractions();
    then(memberRepository).shouldHaveNoInteractions();
    then(refreshTokenIssuer).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("Redis에 없거나 형식이 맞지 않는 Refresh면 INVALID_REFRESH_TOKEN, 회원을 잠그지 않음")
  void reissue_tokenNotFound() {
    given(refreshTokenStore.findOwner(REFRESH_TOKEN)).willReturn(Optional.empty());

    assertInvalidRefreshToken();

    then(memberRepository).shouldHaveNoInteractions();
    then(refreshTokenStore).should(never()).findAndDelete(REFRESH_TOKEN);
    then(jwtProvider).shouldHaveNoInteractions();
    then(refreshTokenIssuer).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("토큰의 회원이 없으면 INVALID_REFRESH_TOKEN, 꺼내지 않고 새 토큰 발급 안 함")
  void reissue_memberNotFound() {
    given(refreshTokenStore.findOwner(REFRESH_TOKEN)).willReturn(Optional.of(OWNER_V0));
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.empty());

    assertInvalidRefreshToken();

    then(refreshTokenStore).should(never()).findAndDelete(REFRESH_TOKEN);
    then(jwtProvider).shouldHaveNoInteractions();
    then(refreshTokenIssuer).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("잠근 뒤 꺼내기에 실패하면(그 사이 다른 요청이 사용) INVALID_REFRESH_TOKEN, 새 토큰 발급 안 함")
  void reissue_takenByOtherRequest() {
    givenFoundAndLocked(seller());
    given(refreshTokenStore.findAndDelete(REFRESH_TOKEN)).willReturn(Optional.empty());

    assertInvalidRefreshToken();

    then(jwtProvider).shouldHaveNoInteractions();
    then(refreshTokenIssuer).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("꺼낸 토큰의 회원이 잠근 회원과 다르면 INVALID_REFRESH_TOKEN, 새 토큰 발급 안 함")
  void reissue_ownerMismatch() {
    givenFoundAndLocked(seller());
    given(refreshTokenStore.findAndDelete(REFRESH_TOKEN)).willReturn(Optional.of(new RefreshTokenOwner(2L, 0)));

    assertInvalidRefreshToken();

    then(jwtProvider).shouldHaveNoInteractions();
    then(refreshTokenIssuer).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("탈퇴 회원이면 INVALID_REFRESH_TOKEN, 새 토큰 발급 안 함")
  void reissue_deletedMember() {
    Member member = seller();
    ReflectionTestUtils.setField(member, "status", MemberStatus.DELETED);
    givenFoundAndLocked(member);
    given(refreshTokenStore.findAndDelete(REFRESH_TOKEN)).willReturn(Optional.of(OWNER_V0));

    assertInvalidRefreshToken();

    then(jwtProvider).shouldHaveNoInteractions();
    then(refreshTokenIssuer).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("꺼낸 Refresh의 인증 버전이 DB와 다르면(비밀번호 변경 · 탈퇴 후) INVALID_REFRESH_TOKEN, Access · 새 Refresh 발급 안 함")
  void reissue_versionMismatch() {
    Member member = seller();
    member.increaseTokenVersion(); // DB 인증 버전 1
    givenFoundAndLocked(member);
    given(refreshTokenStore.findAndDelete(REFRESH_TOKEN)).willReturn(Optional.of(OWNER_V0));

    assertInvalidRefreshToken();

    then(jwtProvider).shouldHaveNoInteractions();
    then(refreshTokenIssuer).should(never()).issue(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    then(databaseClock).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("TokenReissueResult를 문자열로 출력해도 토큰 원문은 보이지 않음")
  void tokenReissueResult_toStringHidesTokens() {
    TokenReissueResult result = new TokenReissueResult("access-token", 1800, "refresh-token", Duration.ofDays(1));

    assertThat(result.toString())
        .doesNotContain("access-token")
        .doesNotContain("refresh-token");
  }
}
