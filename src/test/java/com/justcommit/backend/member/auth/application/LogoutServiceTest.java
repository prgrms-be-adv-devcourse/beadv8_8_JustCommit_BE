package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class LogoutServiceTest {

  @Mock
  private RefreshTokenRedisStore refreshTokenStore;

  @InjectMocks
  private LogoutService logoutService;

  @Test
  @DisplayName("로그아웃: 쿠키의 Refresh 토큰을 Redis에서 삭제")
  void logout_deletesRefreshToken() {
    logoutService.logout("refresh-token");

    then(refreshTokenStore).should().delete("refresh-token");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  @DisplayName("쿠키가 없거나 비어 있어도 예외 없이 끝나고, Redis는 호출하지 않음")
  void logout_noCookie(String refreshToken) {
    assertThatCode(() -> logoutService.logout(refreshToken)).doesNotThrowAnyException();

    then(refreshTokenStore).shouldHaveNoInteractions();
  }
}
