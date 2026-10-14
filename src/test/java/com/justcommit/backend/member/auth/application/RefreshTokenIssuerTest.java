package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.member.infrastructure.redis.RefreshTokenRedisStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
public class RefreshTokenIssuerTest {

  @Mock
  private RefreshTokenRedisStore refreshTokenStore;

  @InjectMocks
  private RefreshTokenIssuer refreshTokenIssuer;

  private static final Long MEMBER_ID = 1L;

  @Test
  @DisplayName("발급한 Refresh 토큰을 회원 id·3시간으로 Redis에 저장하고 원문을 반환")
  void issue_savesTokenWithTtl() {
    String refreshToken = refreshTokenIssuer.issue(MEMBER_ID);

    // then: 반환한 토큰을 그대로 저장
    ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
    then(refreshTokenStore).should().save(tokenCaptor.capture(), eq(MEMBER_ID), eq(Duration.ofDays(1)));
    assertThat(tokenCaptor.getValue()).isEqualTo(refreshToken);

    // then: 32바이트 랜덤 → URL 안전 Base64(패딩 없음) 43자
    assertThat(refreshToken).matches("^[A-Za-z0-9_-]{43}$");
  }

  @Test
  @DisplayName("발급할 때마다 서로 다른 Refresh 토큰")
  void issue_differentTokens() {
    String first = refreshTokenIssuer.issue(MEMBER_ID);
    String second = refreshTokenIssuer.issue(MEMBER_ID);

    assertThat(first).isNotEqualTo(second);
  }
}
