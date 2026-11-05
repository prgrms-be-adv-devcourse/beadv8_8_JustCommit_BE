package com.justcommit.backend.common.client;

import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.registry.ImportHttpServices;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalApiFailureTest {

  private static HttpServer server;

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          HttpClientAutoConfiguration.class,
          ImperativeHttpClientAutoConfiguration.class,
          HttpServiceClientPropertiesAutoConfiguration.class,
          RestClientAutoConfiguration.class,
          HttpServiceClientAutoConfiguration.class,
          InternalApiClientAutoConfiguration.class))
      .withUserConfiguration(ClientsConfig.class)
      .withPropertyValues("internal-api.key=test-key", "internal-api.groups=payment");

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/conflict", exchange -> respond(exchange, 409,
        "{\"status\":409,\"code\":\"INSUFFICIENT_BALANCE\",\"message\":\"잔액이 부족합니다.\"}"));
    server.createContext("/unavailable", exchange -> respond(exchange, 503, null));
    server.createContext("/slow", exchange -> {
      try {
        Thread.sleep(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      respond(exchange, 200, null);
    });
    server.start();
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
  }

  @Test
  @DisplayName("에러 응답은 상대가 보낸 code와 함께 예외로 바꾼다")
  void convertsErrorResponseWithCode() {
    runner.withPropertyValues("spring.http.serviceclient.payment.base-url=" + serverUrl())
        .run(context -> assertThatThrownBy(() -> context.getBean(PaymentClient.class).conflict())
            .isInstanceOfSatisfying(InternalApiErrorException.class, e -> {
              assertThat(e.group()).isEqualTo("payment");
              assertThat(e.status()).isEqualTo(409);
              assertThat(e.code()).isEqualTo("INSUFFICIENT_BALANCE");
              assertThat(e.getMessage()).isEqualTo("잔액이 부족합니다.");
            }));
  }

  @Test
  @DisplayName("본문이 없는 에러 응답도 예외로 바꾼다")
  void convertsErrorResponseWithoutBody() {
    runner.withPropertyValues("spring.http.serviceclient.payment.base-url=" + serverUrl())
        .run(context -> assertThatThrownBy(() -> context.getBean(PaymentClient.class).unavailable())
            .isInstanceOfSatisfying(InternalApiErrorException.class, e -> {
              assertThat(e.status()).isEqualTo(503);
              assertThat(e.code()).isNull();
            }));
  }

  @Test
  @DisplayName("서버에 연결하지 못하면 연결 실패 예외")
  void throwsUnavailableWhenConnectionFails() throws IOException {
    runner.withPropertyValues("spring.http.serviceclient.payment.base-url=http://localhost:" + closedPort())
        .run(context -> assertThatThrownBy(() -> context.getBean(PaymentClient.class).conflict())
            .isInstanceOf(InternalApiUnavailableException.class));
  }

  @Test
  @DisplayName("응답 시간이 초과되면 결과 불명 예외")
  void throwsResultUnknownOnReadTimeout() {
    runner.withPropertyValues(
            "spring.http.serviceclient.payment.base-url=" + serverUrl(),
            "spring.http.serviceclient.payment.read-timeout=500ms")
        .run(context -> assertThatThrownBy(() -> context.getBean(PaymentClient.class).slow())
            .isInstanceOf(InternalApiResultUnknownException.class));
  }

  @Test
  @DisplayName("외부 그룹 에러는 바꾸지 않는다")
  void keepsExternalGroupError() {
    runner.withPropertyValues("spring.http.serviceclient.toss.base-url=" + serverUrl())
        .run(context -> assertThatThrownBy(() -> context.getBean(TossClient.class).conflict())
            .isInstanceOf(RestClientResponseException.class));
  }

  private static void respond(HttpExchange exchange, int status, String json) throws IOException {
    if (json == null) {
      exchange.sendResponseHeaders(status, -1);
    } else {
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  private static String serverUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  private static int closedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  interface PaymentClient {
    @GetExchange("/conflict")
    void conflict();

    @GetExchange("/unavailable")
    void unavailable();

    @GetExchange("/slow")
    void slow();
  }

  interface TossClient {
    @GetExchange("/conflict")
    void conflict();
  }

  @Configuration(proxyBeanMethods = false)
  @ImportHttpServices(group = "payment", types = PaymentClient.class)
  @ImportHttpServices(group = "toss", types = TossClient.class)
  static class ClientsConfig {
  }
}
