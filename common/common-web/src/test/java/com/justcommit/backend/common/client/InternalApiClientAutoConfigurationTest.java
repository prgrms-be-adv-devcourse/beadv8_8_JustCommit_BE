package com.justcommit.backend.common.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.registry.ImportHttpServices;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiClientAutoConfigurationTest {

  private static HttpServer server;
  private static final AtomicReference<String> receivedKey = new AtomicReference<>();
  private static final AtomicReference<String> receivedAuthorization = new AtomicReference<>();

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          HttpClientAutoConfiguration.class,
          ImperativeHttpClientAutoConfiguration.class,
          HttpServiceClientPropertiesAutoConfiguration.class,
          RestClientAutoConfiguration.class,
          HttpServiceClientAutoConfiguration.class,
          InternalApiClientAutoConfiguration.class))
      .withUserConfiguration(ClientsConfig.class);

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/ping", exchange -> {
      receivedKey.set(exchange.getRequestHeaders().getFirst(InternalApiHeaders.INTERNAL_KEY));
      receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
  }

  @BeforeEach
  void resetReceivedHeaders() {
    receivedKey.set(null);
    receivedAuthorization.set(null);
  }

  @Test
  @DisplayName("내부 그룹 호출에는 내부 키를 붙인다")
  void addsInternalKeyToInternalGroup() {
    runner.withPropertyValues(baseUrls("internal-api.key=test-key", "internal-api.groups=payment"))
        .run(context -> {
          context.getBean(PaymentPingClient.class).ping();

          assertThat(receivedKey.get()).isEqualTo("test-key");
        });
  }

  @Test
  @DisplayName("내부 그룹이 아니면 내부 키를 붙이지 않는다")
  void skipsInternalKeyForExternalGroup() {
    runner.withPropertyValues(baseUrls("internal-api.key=test-key", "internal-api.groups=payment"))
        .run(context -> {
          context.getBean(TossPingClient.class).ping();

          assertThat(receivedKey.get()).isNull();
        });
  }

  @Test
  @DisplayName("내부 그룹이 있는데 키가 없으면 시작하지 않는다")
  void failsToStartWithoutKey() {
    runner.withPropertyValues(baseUrls("internal-api.groups=payment"))
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("사용자 요청 중에는 Authorization을 내부 그룹에 전달한다")
  void forwardsAuthorizationToInternalGroup() {
    runWithUserToken("Bearer user-token", () ->
        runner.withPropertyValues(baseUrls("internal-api.key=test-key", "internal-api.groups=payment"))
            .run(context -> {
              context.getBean(PaymentPingClient.class).ping();

              assertThat(receivedAuthorization.get()).isEqualTo("Bearer user-token");
            }));
  }

  @Test
  @DisplayName("외부 그룹에는 사용자 Authorization을 전달하지 않는다")
  void skipsAuthorizationForExternalGroup() {
    runWithUserToken("Bearer user-token", () ->
        runner.withPropertyValues(baseUrls("internal-api.key=test-key", "internal-api.groups=payment"))
            .run(context -> {
              context.getBean(TossPingClient.class).ping();

              assertThat(receivedAuthorization.get()).isNull();
            }));
  }

  private static void runWithUserToken(String authorization, Runnable action) {
    MockHttpServletRequest userRequest = new MockHttpServletRequest();
    userRequest.addHeader("Authorization", authorization);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(userRequest));
    try {
      action.run();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  private static String[] baseUrls(String... extra) {
    String baseUrl = "http://localhost:" + server.getAddress().getPort();
    String[] base = {
        "spring.http.serviceclient.payment.base-url=" + baseUrl,
        "spring.http.serviceclient.toss.base-url=" + baseUrl
    };
    String[] all = new String[base.length + extra.length];
    System.arraycopy(base, 0, all, 0, base.length);
    System.arraycopy(extra, 0, all, base.length, extra.length);
    return all;
  }

  interface PaymentPingClient {
    @GetExchange("/ping")
    void ping();
  }

  interface TossPingClient {
    @GetExchange("/ping")
    void ping();
  }

  @Configuration(proxyBeanMethods = false)
  @ImportHttpServices(group = "payment", types = PaymentPingClient.class)
  @ImportHttpServices(group = "toss", types = TossPingClient.class)
  static class ClientsConfig {
  }
}
