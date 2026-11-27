package com.justcommit.backend.member.support;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.common.testcontainers.SharedRedis;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * member 통합 테스트가 함께 쓰는 스프링 컨텍스트 (#664).
 *
 * <p>스프링은 설정(어노테이션 · {@code @DynamicPropertySource} 메서드 · mock 목록)이 같은 테스트 클래스끼리 컨텍스트를 재사용한다.
 * 클래스마다 설정을 따로 두면 클래스마다 컨텍스트 기동 + 빈 DB + Flyway 전체 실행을 새로 치르므로, 설정은 여기에만 둔다.
 *
 * <p>규칙
 * <ul>
 *   <li>상속한 클래스는 {@code @SpringBootTest} · {@code @DynamicPropertySource} · {@code @MockitoBean}을 따로 두지 않는다.
 *       따로 두면 그 클래스만 컨텍스트를 새로 띄운다 (꼭 필요하면 이 클래스를 상속하지 않는다)</li>
 *   <li>DB 테이블과 Redis는 테스트 클래스가 시작하기 전에 비운다 ({@link ResetDataBeforeClassListener}).
 *       예전 「컨텍스트마다 빈 DB」와 같은 격리다</li>
 *   <li>JWT 키는 테스트 실행 동안 하나({@link #JWT_KEY_PAIR})를 같이 쓴다. 토큰을 직접 서명할 때 이 키를 쓴다</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@TestExecutionListeners(listeners = ResetDataBeforeClassListener.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public abstract class MemberIntegrationTest {

  protected static final KeyPair JWT_KEY_PAIR = newKeyPair();

  @DynamicPropertySource
  static void sharedProperties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
    SharedRedis.register(registry);
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
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
