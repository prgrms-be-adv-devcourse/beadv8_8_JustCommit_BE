package com.justcommit.backend.member.auth.presentation;

import com.justcommit.backend.member.support.MemberIntegrationTest;
import com.justcommit.backend.common.security.TokenHash;
import com.justcommit.backend.member.auth.application.RefreshTokenIssuer;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그아웃을 실제 HTTP로 호출해 member의 보안 필터 · 쿠키 · Redis까지 확인한다.
 * 검증 범위는 member 서비스의 보안 필터까지이며, Gateway를 거치는 경우는 아니다.
 */
class LogoutIntegrationTest extends MemberIntegrationTest {

  @Value("${local.server.port}")
  private int port;

  @Autowired
  private JwtProvider jwtProvider;

  @Autowired
  private RefreshTokenIssuer refreshTokenIssuer;

  @Autowired
  private StringRedisTemplate redisTemplate;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @BeforeEach
  void cleanRedis() {
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {"expired", "broken"})
  @DisplayName("만료 · 깨진 Bearer + 유효한 Refresh: 필터를 통과해 200, Refresh는 삭제되고 블랙리스트에는 없다")
  void invalidAccessStillLogsOut(String kind) throws Exception {
    String refreshToken = refreshTokenIssuer.issue(4L, 0);
    String accessToken = "expired".equals(kind) ? expiredAccessToken() : "not-a-jwt";

    HttpResponse<String> response = logout(accessToken, refreshToken);

    assertThat(response.statusCode()).isEqualTo(200);
    assertRefreshCookieExpired(response);
    assertThat(redisTemplate.hasKey("auth:refresh:" + TokenHash.sha256Hex(refreshToken))).isFalse();
    assertThat(redisTemplate.keys("auth:blacklist:*")).isEmpty();
  }

  @Test
  @DisplayName("[S-A04] 유효한 Bearer + Refresh: 200, Refresh 삭제, Access는 남은 수명 동안 블랙리스트에 들어간다")
  void validAccessIsBlacklisted() throws Exception {
    String refreshToken = refreshTokenIssuer.issue(4L, 0);
    String accessToken = jwtProvider.createAccessToken(4L, "MEMBER", Instant.now());

    HttpResponse<String> response = logout(accessToken, refreshToken);

    assertThat(response.statusCode()).isEqualTo(200);
    assertRefreshCookieExpired(response);
    assertThat(redisTemplate.hasKey("auth:refresh:" + TokenHash.sha256Hex(refreshToken))).isFalse();
    String blacklistKey = "auth:blacklist:" + TokenHash.sha256Hex(accessToken);
    assertThat(redisTemplate.opsForValue().get(blacklistKey)).isEqualTo("1");
    assertThat(redisTemplate.getExpire(blacklistKey)).isBetween(1L, Duration.ofMinutes(30).toSeconds());
  }

  @Test
  @DisplayName("두 토큰이 모두 없어도 200이고 Refresh 쿠키를 만료한다")
  void noTokensStillOk() throws Exception {
    HttpResponse<String> response = logout(null, null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertRefreshCookieExpired(response);
  }

  private HttpResponse<String> logout(String accessToken, String refreshToken) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/logout"))
        .POST(HttpRequest.BodyPublishers.noBody());
    if (accessToken != null) {
      request.header("Authorization", "Bearer " + accessToken);
    }
    if (refreshToken != null) {
      request.header("Cookie", "refreshToken=" + refreshToken);
    }
    return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  // 로그인 때와 같은 이름 · Path로 Max-Age=0 → 브라우저가 그 쿠키를 지움
  private static void assertRefreshCookieExpired(HttpResponse<String> response) {
    List<String> cookies = response.headers().allValues("set-cookie");
    assertThat(cookies).anySatisfy(cookie -> assertThat(cookie)
        .startsWith("refreshToken=;")
        .contains("Path=/api/v1/auth")
        .contains("Max-Age=0"));
  }

  // 같은 키로 서명했지만 이미 만료된 Access 토큰
  private static String expiredAccessToken() {
    Instant past = Instant.now().minus(Duration.ofHours(1));
    return Jwts.builder()
        .subject("4")
        .claim("type", "ACCESS")
        .claim("role", "MEMBER")
        .issuedAt(Date.from(past))
        .expiration(Date.from(past.plusSeconds(60)))
        .signWith(JWT_KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
        .compact();
  }
}
