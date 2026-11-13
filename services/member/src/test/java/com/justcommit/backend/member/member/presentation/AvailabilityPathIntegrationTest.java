package com.justcommit.backend.member.member.presentation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 닉네임 · 휴대폰 중복 확인 경로를 실제 HTTP로 호출해 member 보안 필터의 공개 경로를 확인한다 (#423).
 * Gateway 쪽 공개 경로는 gateway의 JwtAuthenticationIntegrationTest에서 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class AvailabilityPathIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  // 실행마다 새 RSA 키 쌍 (MemberApplicationTests와 같은 방식)
  private static final KeyPair JWT_KEY_PAIR = newKeyPair();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
  }

  @Value("${local.server.port}")
  private int port;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  @DisplayName("닉네임 · 휴대폰 중복 확인 새 경로는 토큰 없이 200이고 사용 가능 여부를 돌려준다")
  void newPathsArePublic() throws Exception {
    HttpResponse<String> nickname = get("/api/v1/users/nicknameAvailability?nickname=green");
    HttpResponse<String> phone = get("/api/v1/users/phoneAvailability?phone=01012345678");

    assertThat(nickname.statusCode()).isEqualTo(200);
    assertThat(nickname.body()).contains("\"available\":true");
    assertThat(phone.statusCode()).isEqualTo(200);
    assertThat(phone.body()).contains("\"available\":true");
  }

  @Test
  @DisplayName("옛 경로(checkNickname · checkPhone)는 남지 않아 토큰 없이 401이다")
  void oldPathsAreGone() throws Exception {
    assertThat(get("/api/v1/users/checkNickname?nickname=green").statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/users/checkPhone?phone=01012345678").statusCode()).isEqualTo(401);
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static KeyPair newKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
