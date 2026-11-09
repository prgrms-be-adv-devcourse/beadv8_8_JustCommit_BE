package com.justcommit.backend.common.client;

import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
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
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.registry.ImportHttpServices;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalApiBodyReadTest {

  private static HttpServer server;

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          HttpClientAutoConfiguration.class,
          ImperativeHttpClientAutoConfiguration.class,
          HttpServiceClientPropertiesAutoConfiguration.class,
          RestClientAutoConfiguration.class,
          HttpServiceClientAutoConfiguration.class,
          InternalApiClientAutoConfiguration.class))
      .withUserConfiguration(BodyClientsConfig.class);

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);

    // 헤더는 바로 보내고 본문은 절반만 보낸 뒤 멈춤 (본문을 읽다 시간 초과)
    server.createContext("/slow-body", exchange -> {
      byte[] first = "{\"value\":".getBytes(StandardCharsets.UTF_8);
      byte[] rest = "\"done\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, first.length + rest.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(first);
        out.flush();
        sleep(1500);
        out.write(rest);
      } catch (IOException ignored) {
        // 클라이언트가 먼저 끊으면 쓰기 실패는 무시
      }
    });

    server.createContext("/ok", exchange -> {
      byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "text/plain");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });

    // 느린 응답이 처리되는 동안 다른 요청이 막히지 않게 요청마다 스레드를 씀
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
  }

  @Test
  @DisplayName("응답 본문을 읽다 시간이 초과되면 RestClientException이 아니라 결과 불명 예외")
  void bodyReadTimeoutIsResultUnknown() {
    runner.withPropertyValues(properties())
        .run(context -> assertThatThrownBy(() -> context.getBean(BodyClient.class).slowBody())
            .isInstanceOf(InternalApiResultUnknownException.class));
  }

  @Test
  @DisplayName("본문을 미리 읽어 두어도 정상 응답의 본문은 그대로 전달된다")
  void bufferedBodyIsDelivered() {
    runner.withPropertyValues(properties())
        .run(context -> assertThat(context.getBean(BodyClient.class).ok()).isEqualTo("hello"));
  }

  private static String[] properties() {
    return new String[] {
        "spring.http.serviceclient.payment.base-url=http://localhost:" + server.getAddress().getPort(),
        "spring.http.serviceclient.payment.read-timeout=500ms",
        "internal-api.key=test-key",
        "internal-api.groups=payment"
    };
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  interface BodyClient {
    @GetExchange("/slow-body")
    String slowBody();

    @GetExchange("/ok")
    String ok();
  }

  @Configuration(proxyBeanMethods = false)
  @ImportHttpServices(group = "payment", types = BodyClient.class)
  static class BodyClientsConfig {
  }
}
