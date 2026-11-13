package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.security.TokenException;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.auth.infrastructure.redis.AccessTokenBlacklistStore;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class LogoutServiceTest {

  private static final Instant EXPIRES_AT = Instant.parse("2026-11-13T05:00:00Z");

  @Mock
  private RefreshTokenRedisStore refreshTokenStore;

  @Mock
  private AccessTokenBlacklistStore accessTokenBlacklistStore;

  @Mock
  private JwtProvider jwtProvider;

  @InjectMocks
  private LogoutService logoutService;

  @Test
  @DisplayName("유효한 Access와 Refresh: Refresh를 삭제하고, 검증한 exp로 Access를 블랙리스트에 넣는다")
  void logout_validTokens() {
    given(jwtProvider.getVerifiedAccessTokenExpiresAt("access-token")).willReturn(EXPIRES_AT);

    logoutService.logout("access-token", "refresh-token");

    then(refreshTokenStore).should().delete("refresh-token");
    then(accessTokenBlacklistStore).should().add("access-token", EXPIRES_AT);
  }

  @ParameterizedTest
  @EnumSource(TokenException.Reason.class)
  @DisplayName("만료 · 잘못된 Access: 예외 없이 Refresh는 삭제하고 블랙리스트에는 넣지 않는다")
  void logout_invalidAccessStillDeletesRefresh(TokenException.Reason reason) {
    given(jwtProvider.getVerifiedAccessTokenExpiresAt("bad-access")).willThrow(new TokenException(reason));

    assertThatCode(() -> logoutService.logout("bad-access", "refresh-token")).doesNotThrowAnyException();

    then(refreshTokenStore).should().delete("refresh-token");
    then(accessTokenBlacklistStore).shouldHaveNoInteractions();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  @DisplayName("두 토큰이 모두 없거나 비어 있으면 예외 없이 끝나고 아무것도 호출하지 않는다")
  void logout_noTokens(String token) {
    assertThatCode(() -> logoutService.logout(token, token)).doesNotThrowAnyException();

    then(refreshTokenStore).shouldHaveNoInteractions();
    then(accessTokenBlacklistStore).shouldHaveNoInteractions();
    then(jwtProvider).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("블랙리스트 저장 중 Redis 오류는 성공으로 숨기지 않고 그대로 전달한다 (Refresh 삭제는 이미 진행됨)")
  void logout_blacklistRedisErrorPropagates() {
    given(jwtProvider.getVerifiedAccessTokenExpiresAt("access-token")).willReturn(EXPIRES_AT);
    willThrow(new RedisConnectionFailureException("redis down"))
        .given(accessTokenBlacklistStore).add("access-token", EXPIRES_AT);

    assertThatThrownBy(() -> logoutService.logout("access-token", "refresh-token"))
        .isInstanceOf(RedisConnectionFailureException.class);

    then(refreshTokenStore).should().delete("refresh-token");
  }

  @Test
  @DisplayName("Refresh 삭제 중 Redis 오류도 성공으로 숨기지 않고 그대로 전달한다")
  void logout_refreshRedisErrorPropagates() {
    willThrow(new RedisConnectionFailureException("redis down"))
        .given(refreshTokenStore).delete("refresh-token");

    assertThatThrownBy(() -> logoutService.logout("access-token", "refresh-token"))
        .isInstanceOf(RedisConnectionFailureException.class);

    then(jwtProvider).shouldHaveNoInteractions();
    then(accessTokenBlacklistStore).should(org.mockito.Mockito.never()).add(anyString(), any());
  }
}
