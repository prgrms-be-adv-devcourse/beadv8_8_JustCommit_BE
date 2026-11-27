package com.justcommit.backend.member.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.common.testcontainers.SharedRedis;
import com.justcommit.backend.member.auth.application.TokenRevocationService;
import com.justcommit.backend.member.auth.infrastructure.mail.AccountReminderMailSender;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import com.justcommit.backend.member.auth.infrastructure.redis.RevokedMemberRedisStore;
import com.justcommit.backend.member.auth.infrastructure.mail.VerificationMailSender;
import com.justcommit.backend.member.config.MailExecutorConfig;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import com.justcommit.backend.member.unlink.infrastructure.mail.KakaoUnlinkNoticeMailSender;
import com.justcommit.backend.member.unlink.infrastructure.repository.KakaoUnlinkReceiptRepository;
import com.justcommit.backend.member.withdrawal.application.WithdrawalCheckService;
import com.justcommit.backend.member.withdrawal.infrastructure.kakao.KakaoAdminClient;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
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
 *   <li>메일 발송기 3종은 항상 mock이다 (스프링이 테스트마다 호출 기록을 지운다). 보낸 내용은 이 필드로 검증한다</li>
 *   <li>메일 실행기는 요청 스레드에서 바로 실행한다(응답 직후 발송 호출을 바로 검증). 실제 비동기 동작은
 *       {@code MailAsyncSendIntegrationTest}가 이 클래스를 상속하지 않고 따로 확인한다 (#421)</li>
 *   <li>경합 · 장애를 만들 때 동작을 바꾸는 빈은 spy다. 평소에는 실제 빈 그대로이고, 테스트에서 바꾼 동작은
 *       스프링이 테스트마다 되돌린다. 새로 바꿔야 하는 빈이 생기면 여기에 spy로 추가한다</li>
 *   <li>바깥 API(카카오 · market · payment · settlement)는 WireMock 서버 하나({@link #EXTERNAL_API})가 흉내 낸다.
 *       응답 설정과 받은 요청 기록은 테스트마다 지운다</li>
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

  /** 테스트 JVM에서 한 번만 띄우는 WireMock. 컨텍스트가 이 주소를 들고 있으므로 끄지 않는다 (JVM이 끝날 때 같이 내려감) */
  protected static final WireMockServer EXTERNAL_API = startWireMock();

  @MockitoBean
  protected VerificationMailSender verificationMailSender;

  @MockitoBean
  protected AccountReminderMailSender reminderMailSender;

  @MockitoBean
  protected KakaoUnlinkNoticeMailSender noticeMailSender;

  @MockitoSpyBean
  protected DatabaseClock databaseClock;

  @MockitoSpyBean
  protected RefreshTokenRedisStore refreshTokenRedisStore;

  @MockitoSpyBean
  protected RevokedMemberRedisStore revokedMemberRedisStore;

  @MockitoSpyBean
  protected TokenRevocationService tokenRevocationService;

  @MockitoSpyBean
  protected TransactionTemplate transactionTemplate;

  @MockitoSpyBean
  protected AccountCipher accountCipher;

  @MockitoSpyBean
  protected KakaoUnlinkReceiptRepository receiptRepository;

  @MockitoSpyBean
  protected WithdrawalCheckService withdrawalCheckService;

  @MockitoSpyBean
  protected KakaoAdminClient kakaoAdminClient;

  @TestBean(name = MailExecutorConfig.MAIL_TASK_EXECUTOR)
  protected TaskExecutor mailTaskExecutor;

  static TaskExecutor mailTaskExecutor() {
    return new SyncTaskExecutor();
  }

  @DynamicPropertySource
  static void sharedProperties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
    SharedRedis.register(registry);
    registry.add("member.kakao.api-base-url", EXTERNAL_API::baseUrl);
    registry.add("spring.http.serviceclient.market.base-url", EXTERNAL_API::baseUrl);
    registry.add("spring.http.serviceclient.payment.base-url", EXTERNAL_API::baseUrl);
    registry.add("spring.http.serviceclient.settlement.base-url", EXTERNAL_API::baseUrl);
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
  }

  @BeforeEach
  void resetExternalApi() {
    EXTERNAL_API.resetAll();
  }

  private static WireMockServer startWireMock() {
    WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
    server.start();
    return server;
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
