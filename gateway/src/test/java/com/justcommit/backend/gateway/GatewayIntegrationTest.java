package com.justcommit.backend.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;

/**
 * Gateway 통합 테스트 공통 준비. 진짜 Gateway를 빈 포트로 띄우고
 * - Redis: Testcontainers (토큰 폐기 키 · 요청 수 제한)
 * - 뒤의 서비스 6개: WireMock 하나가 모두 흉내 (무엇이 도착했는지 verify로 확인)
 * - RSA 키: 테스트 실행마다 새로 만들고 공개키만 Gateway에 넣음 (키를 파일에 남기지 않음)
 * Redis · WireMock은 테스트 클래스끼리 공유해서 한 번만 띄운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class GatewayIntegrationTest {

  protected static final KeyPair KEY_PAIR = rsaKeyPair();
  protected static final KeyPair OTHER_KEY_PAIR = rsaKeyPair();

  @SuppressWarnings("resource")
  protected static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);
  protected static final WireMockServer SERVICES = new WireMockServer(wireMockConfig().dynamicPort());

  static {
    REDIS.start();
    SERVICES.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()));
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    for (String service : new String[] {"MEMBER", "PRODUCT", "MARKET", "PAYMENT", "SETTLEMENT", "NOTIFICATION"}) {
      registry.add(service + "_SERVICE_URL", SERVICES::baseUrl);
    }
  }

  @LocalServerPort
  private int port;

  @Autowired
  protected ReactiveStringRedisTemplate redis;

  protected WebTestClient client;

  @BeforeEach
  void setUpGatewayTest() {
    client = WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .responseTimeout(Duration.ofSeconds(15))
        .build();
    SERVICES.resetAll();
    // 기본: 서비스는 어떤 요청이든 200
    SERVICES.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("service-ok")));
    redis.getConnectionFactory().getReactiveConnection().serverCommands().flushAll().block();
  }

  /** member가 발급하는 것과 같은 모양의 Access 토큰 (sub · role · type · iat · exp) */
  protected static String accessToken(long memberId, String role) {
    Instant now = Instant.now();
    return token(KEY_PAIR.getPrivate(), memberId, role, "ACCESS", now.minusSeconds(60), now.plusSeconds(600));
  }

  protected static String token(PrivateKey key, long memberId, String role, String type,
                                Instant issuedAt, Instant expiresAt) {
    return Jwts.builder()
        .subject(String.valueOf(memberId))
        .claim("role", role)
        .claim("type", type)
        .issuedAt(Date.from(issuedAt))
        .expiration(Date.from(expiresAt))
        .signWith(key)
        .compact();
  }

  private static KeyPair rsaKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
