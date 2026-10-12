package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.JwtProvider;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Role;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.redis.RefreshTokenRedisStore;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

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

  @InjectMocks
  private TokenReissueService tokenReissueService;

  private static final String REFRESH_TOKEN = "old-refresh-token";
  private static final Long MEMBER_ID = 1L;

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

  @Test
  @DisplayName("재발급 성공: 기존 Refresh를 폐기하고 DB의 현재 role로 새 Access·Refresh 발급")
  void reissue_success() {
    // given
    given(refreshTokenStore.findAndDelete(REFRESH_TOKEN)).willReturn(Optional.of(MEMBER_ID));
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(seller()));
    given(jwtProvider.createAccessToken(MEMBER_ID, "SELLER")).willReturn("new-access-token");
    given(refreshTokenIssuer.issue(MEMBER_ID)).willReturn("new-refresh-token");

    // when
    TokenReissueResult result = tokenReissueService.reissue(REFRESH_TOKEN);

    // then: 새 토큰과 유효 시간
    assertThat(result.accessToken()).isEqualTo("new-access-token");
    assertThat(result.expiresIn()).isEqualTo(1800);
    assertThat(result.refreshToken()).isEqualTo("new-refresh-token");
    assertThat(result.refreshTokenTtl()).isEqualTo(Duration.ofDays(1));
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
    then(refreshTokenIssuer).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("Redis에 없는 Refresh(만료·이미 사용·위조)면 INVALID_REFRESH_TOKEN, 새 토큰 발급 안 함")
  void reissue_tokenNotFound() {
    given(refreshTokenStore.findAndDelete(REFRESH_TOKEN)).willReturn(Optional.empty());

    assertThatThrownBy(() -> tokenReissueService.reissue(REFRESH_TOKEN))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN);

    then(memberRepository).shouldHaveNoInteractions();
    then(jwtProvider).shouldHaveNoInteractions();
    then(refreshTokenIssuer).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("토큰의 회원이 없으면(탈퇴 등) INVALID_REFRESH_TOKEN, 새 토큰 발급 안 함")
  void reissue_memberNotFound() {
    given(refreshTokenStore.findAndDelete(REFRESH_TOKEN)).willReturn(Optional.of(MEMBER_ID));
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> tokenReissueService.reissue(REFRESH_TOKEN))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN);

    then(jwtProvider).shouldHaveNoInteractions();
    then(refreshTokenIssuer).shouldHaveNoInteractions();
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
