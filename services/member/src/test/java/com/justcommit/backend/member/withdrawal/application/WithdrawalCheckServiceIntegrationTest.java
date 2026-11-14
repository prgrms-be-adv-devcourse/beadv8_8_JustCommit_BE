package com.justcommit.backend.member.withdrawal.application;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴 가능 여부 확인 (#406)을 WireMock(market · payment · settlement 대역)으로 검증한다.
 * 실제 공통 클라이언트 설정(내부 키 · 오류 변환 · 타임아웃)을 거치도록 기존 member 통합 테스트 구성(@SpringBootTest + PostgreSQL)을 재사용한다.
 * 확인하지 않는 것: 실제 서비스와의 연동, 연결 실패 → 재시도 경로(응답 중 연결 끊김만 확인), traceId 전파.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class WithdrawalCheckServiceIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final ParallelGate GATE = new ParallelGate();

  @RegisterExtension
  static final WireMockExtension SERVICES = WireMockExtension.newInstance()
      .options(wireMockConfig().dynamicPort().extensions(GATE))
      .build();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    // 세 그룹 모두 같은 WireMock으로 (경로가 달라 구분됨)
    registry.add("spring.http.serviceclient.market.base-url", SERVICES::baseUrl);
    registry.add("spring.http.serviceclient.payment.base-url", SERVICES::baseUrl);
    registry.add("spring.http.serviceclient.settlement.base-url", SERVICES::baseUrl);
  }

  private static final Long MEMBER_ID = 1L;
  private static final String MARKET = "/internal/v1/orders/members/1/withdrawalCheck";
  private static final String PAYMENT = "/internal/v1/payments/members/1/withdrawalCheck";
  private static final String SETTLEMENT = "/internal/v1/settlements/members/1/withdrawalCheck";

  private static final String PASS_DATA = """
      {"hasWithdrawalBlocker": false, "blockingReasons": [], "details": {}}""";

  @Autowired
  private WithdrawalCheckService withdrawalCheckService;

  @BeforeEach
  void setUp() {
    GATE.reset();
  }

  @AfterEach
  void releaseGate() {
    // 병렬 테스트가 실패해도 보류 중인 응답을 반드시 풀어 다음 테스트 · 종료가 막히지 않게 함
    GATE.release();
  }

  @Test
  @DisplayName("세 곳 모두 차단 없음이면 ALLOWED")
  void allowed() {
    stubAll(ok(PASS_DATA));

    WithdrawalCheckResult result = withdrawalCheckService.check(MEMBER_ID);

    assertThat(result.status()).isEqualTo(WithdrawalCheckStatus.ALLOWED);
    assertThat(result.isAllowed()).isTrue();
    assertThat(result.blockers()).isEmpty();
    assertThat(result.failedServices()).isEmpty();
  }

  @Test
  @DisplayName("정상 응답 중 차단이 있으면 BLOCKED, 사유와 details를 그대로 담음")
  void blocked() {
    stubAll(ok(PASS_DATA));
    stub(MARKET, ok("""
        {"hasWithdrawalBlocker": true, "blockingReasons": ["ACTIVE_BUYER_ORDER"],
         "details": {"activeBuyerOrderCount": 2, "activeSellerOrderCount": 0}}"""));

    WithdrawalCheckResult result = withdrawalCheckService.check(MEMBER_ID);

    assertThat(result.status()).isEqualTo(WithdrawalCheckStatus.BLOCKED);
    assertThat(result.isAllowed()).isFalse();
    assertThat(result.failedServices()).isEmpty();
    assertThat(result.blockers()).singleElement().satisfies(blocker -> {
      assertThat(blocker.service()).isEqualTo("market");
      assertThat(blocker.reasons()).containsExactly("ACTIVE_BUYER_ORDER");
      assertThat(blocker.details()).containsEntry("activeBuyerOrderCount", 2);
    });
  }

  @Test
  @DisplayName("한 곳이 500이면 UNAVAILABLE")
  void serverError() {
    stubAll(ok(PASS_DATA));
    stub(PAYMENT, aResponse().withStatus(500));

    WithdrawalCheckResult result = withdrawalCheckService.check(MEMBER_ID);

    assertThat(result.status()).isEqualTo(WithdrawalCheckStatus.UNAVAILABLE);
    assertThat(result.failedServices()).containsExactly("payment");
  }

  @Test
  @DisplayName("한 곳이 404이면 UNAVAILABLE")
  void notFound() {
    stubAll(ok(PASS_DATA));
    stub(SETTLEMENT, aResponse().withStatus(404));

    WithdrawalCheckResult result = withdrawalCheckService.check(MEMBER_ID);

    assertThat(result.status()).isEqualTo(WithdrawalCheckStatus.UNAVAILABLE);
    assertThat(result.failedServices()).containsExactly("settlement");
  }

  @Test
  @DisplayName("한 곳의 응답이 응답 타임아웃(3초)보다 늦으면 UNAVAILABLE")
  void responseTimeout() {
    stubAll(ok(PASS_DATA));
    stub(SETTLEMENT, ok(PASS_DATA).withFixedDelay(3_500));

    WithdrawalCheckResult result = withdrawalCheckService.check(MEMBER_ID);

    assertThat(result.status()).isEqualTo(WithdrawalCheckStatus.UNAVAILABLE);
    assertThat(result.failedServices()).containsExactly("settlement");
  }

  @Test
  @DisplayName("한 곳이 응답 중 연결을 끊으면 UNAVAILABLE (연결 실패 재시도 경로는 이 테스트 범위 아님)")
  void connectionResetDuringResponse() {
    stubAll(ok(PASS_DATA));
    stub(PAYMENT, aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

    WithdrawalCheckResult result = withdrawalCheckService.check(MEMBER_ID);

    assertThat(result.status()).isEqualTo(WithdrawalCheckStatus.UNAVAILABLE);
    assertThat(result.failedServices()).containsExactly("payment");
  }

  static Stream<Arguments> invalidResponses() {
    return Stream.of(
        Arguments.of("200 본문 없음", null),
        Arguments.of("data 없음", """
            {"status": 200, "code": "OK", "message": "ok"}"""),
        Arguments.of("hasWithdrawalBlocker 누락", wrap("""
            {"blockingReasons": [], "details": {}}""")),
        Arguments.of("hasWithdrawalBlocker null", wrap("""
            {"hasWithdrawalBlocker": null, "blockingReasons": [], "details": {}}""")),
        Arguments.of("blockingReasons 누락", wrap("""
            {"hasWithdrawalBlocker": false, "details": {}}""")),
        Arguments.of("blockingReasons null", wrap("""
            {"hasWithdrawalBlocker": false, "blockingReasons": null, "details": {}}""")),
        Arguments.of("false + 사유 있음", wrap("""
            {"hasWithdrawalBlocker": false, "blockingReasons": ["ACTIVE_BUYER_ORDER"], "details": {}}""")),
        Arguments.of("true + 빈 사유", wrap("""
            {"hasWithdrawalBlocker": true, "blockingReasons": [], "details": {}}""")),
        Arguments.of("깨진 JSON", """
            {"status": 200, "data": {"hasWithdrawalBlocker": """)
    );
  }

  @ParameterizedTest(name = "{0} → UNAVAILABLE")
  @MethodSource("invalidResponses")
  @DisplayName("잘못된 응답이면 UNAVAILABLE")
  void invalidResponse(String caseName, String body) {
    stubAll(ok(PASS_DATA));
    ResponseDefinitionBuilder response = aResponse().withStatus(200).withHeader("Content-Type", "application/json");
    if (body != null) {
      response.withBody(body);
    }
    stub(MARKET, response);

    WithdrawalCheckResult result = withdrawalCheckService.check(MEMBER_ID);

    assertThat(result.status()).isEqualTo(WithdrawalCheckStatus.UNAVAILABLE);
    assertThat(result.failedServices()).containsExactly("market");
  }

  @Test
  @DisplayName("차단과 실패가 함께 있으면 UNAVAILABLE, 받은 차단 사유는 유지")
  void blockedAndFailed() {
    stubAll(ok(PASS_DATA));
    stub(MARKET, ok("""
        {"hasWithdrawalBlocker": true, "blockingReasons": ["ACTIVE_SELLER_ORDER"],
         "details": {"activeBuyerOrderCount": 0, "activeSellerOrderCount": 1}}"""));
    stub(PAYMENT, aResponse().withStatus(500));

    WithdrawalCheckResult result = withdrawalCheckService.check(MEMBER_ID);

    assertThat(result.status()).isEqualTo(WithdrawalCheckStatus.UNAVAILABLE);
    assertThat(result.failedServices()).containsExactly("payment");
    assertThat(result.blockers()).singleElement().satisfies(blocker -> {
      assertThat(blocker.service()).isEqualTo("market");
      assertThat(blocker.reasons()).containsExactly("ACTIVE_SELLER_ORDER");
    });
  }

  @Test
  @DisplayName("세 호출이 병렬로 나간다: 세 요청 모두 보류 중에 나머지 요청이 도착함")
  void callsInParallel() {
    stubAll(ok(PASS_DATA).withTransformers(ParallelGate.NAME));

    WithdrawalCheckResult result = withdrawalCheckService.check(MEMBER_ID);

    // 요청마다 "기다리는 2초 안에 세 요청이 모두 도착했는가"를 셈. 순차 실행이면 3이 될 수 없음
    assertThat(GATE.satisfiedRequests()).isEqualTo(3);
    assertThat(result.status()).isEqualTo(WithdrawalCheckStatus.ALLOWED);
  }

  @Test
  @DisplayName("세 요청 모두 내부 키는 보내고 사용자 Authorization은 보내지 않음")
  void sendsInternalKeyWithoutAuthorization() {
    stubAll(ok(PASS_DATA));

    withdrawalCheckService.check(MEMBER_ID);

    for (String path : new String[] {MARKET, PAYMENT, SETTLEMENT}) {
      SERVICES.verify(getRequestedFor(urlPathEqualTo(path))
          .withHeader("X-Internal-Key", equalTo("test-internal-key"))
          .withHeader("Authorization", absent()));
    }
  }

  private static ResponseDefinitionBuilder ok(String data) {
    return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(wrap(data));
  }

  // 공통 ApiResponse 형식으로 감쌈
  private static String wrap(String data) {
    return "{\"status\": 200, \"code\": \"OK\", \"message\": \"ok\", \"data\": " + data + "}";
  }

  private static void stubAll(ResponseDefinitionBuilder response) {
    stub(MARKET, response);
    stub(PAYMENT, response);
    stub(SETTLEMENT, response);
  }

  // 같은 경로에 나중에 등록한 응답이 우선 (WireMock 기본 동작)
  private static void stub(String path, ResponseDefinitionBuilder response) {
    SERVICES.stubFor(get(urlPathEqualTo(path)).willReturn(response));
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

  /**
   * 병렬 확인용 WireMock 확장. 이 확장을 붙인 응답만, 세 요청이 모두 도착할 때까지(최대 2초) 응답을 보류한다.
   * 2초는 클라이언트 응답 타임아웃(3초)보다 짧게 둬서, 병렬이 아니면 빨리 풀리고 판정이 실패하게 한다.
   */
  static final class ParallelGate implements ResponseDefinitionTransformerV2 {

    static final String NAME = "parallel-gate";
    private static final int EXPECTED = 3;

    private volatile CountDownLatch arrivals = new CountDownLatch(EXPECTED);
    private final AtomicInteger satisfied = new AtomicInteger();

    void reset() {
      arrivals = new CountDownLatch(EXPECTED);
      satisfied.set(0);
    }

    // 보류 중인 요청을 모두 풀어 줌 (테스트 실패 시에도 호출)
    void release() {
      CountDownLatch latch = arrivals;
      while (latch.getCount() > 0) {
        latch.countDown();
      }
    }

    int satisfiedRequests() {
      return satisfied.get();
    }

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
      CountDownLatch latch = arrivals;
      latch.countDown();
      try {
        if (latch.await(2, TimeUnit.SECONDS)) {
          satisfied.incrementAndGet();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return serveEvent.getResponseDefinition();
    }

    @Override
    public boolean applyGlobally() {
      return false;
    }

    @Override
    public String getName() {
      return NAME;
    }
  }
}
