package com.justcommit.backend.payment.infrastructure.pg;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 로컬 HTTP 서버로 토스 응답을 흉내 내 결제 조회 결과 분류를 확인한다
class TossPaymentClientTest {

    private static final String ORDER_ID = "CHG-order";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

    private HttpServer server;
    private TossPaymentClient client;
    private final AtomicReference<Response> next = new AtomicReference<>();
    private final AtomicReference<String> requestedPath = new AtomicReference<>();

    private record Response(int status, String body, long delayMillis) {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            Response response = next.get();
            try {
                Thread.sleep(response.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } catch (IOException ignored) {
                // 타임아웃 테스트에서 클라이언트가 먼저 끊은 경우
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new TossPaymentClient("test_sk_dummy", baseUrl, Duration.ofSeconds(1), READ_TIMEOUT);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("200이면 orderId 조회 경로로 받은 결제 정보를 돌려준다")
    void returnsPaymentOn200() {
        respond(200, """
                {"paymentKey":"pk","orderId":"CHG-order","status":"DONE","totalAmount":10000,
                 "approvedAt":"2026-11-15T10:00:00+09:00","method":"카드"}
                """);

        TossPaymentResponse response = client.getPaymentByOrderId(ORDER_ID);

        assertThat(requestedPath.get()).isEqualTo("/v1/payments/orders/" + ORDER_ID);
        assertThat(response.status()).isEqualTo("DONE");
        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.totalAmount()).isEqualTo(10000L);
        assertThat(response.approvedAt()).isNotNull();
    }

    @Test
    @DisplayName("404면 TossPaymentNotFoundException")
    void throwsNotFoundOn404() {
        respond(404, """
                {"code":"NOT_FOUND_PAYMENT","message":"존재하지 않는 결제 정보 입니다."}
                """);

        assertThatThrownBy(() -> client.getPaymentByOrderId(ORDER_ID))
                .isInstanceOf(TossPaymentNotFoundException.class)
                .hasMessageContaining("NOT_FOUND_PAYMENT");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    @DisplayName("401, 403이면 TossLookupUnauthorizedException")
    void throwsUnauthorizedOn401And403(int status) {
        respond(status, """
                {"code":"UNAUTHORIZED_KEY","message":"인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."}
                """);

        assertThatThrownBy(() -> client.getPaymentByOrderId(ORDER_ID))
                .isInstanceOf(TossLookupUnauthorizedException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 429, 500, 503})
    @DisplayName("그 밖의 4xx, 5xx면 TossLookupFailedException")
    void throwsFailedOnOtherErrors(int status) {
        respond(status, """
                {"code":"SOME_ERROR","message":"오류"}
                """);

        assertThatThrownBy(() -> client.getPaymentByOrderId(ORDER_ID))
                .isInstanceOf(TossLookupFailedException.class);
    }

    @Test
    @DisplayName("오류 응답 바디가 JSON이 아니어도 상태 코드로 분류한다")
    void classifiesByStatusWhenBodyIsNotJson() {
        respond(502, "<html>bad gateway</html>");

        assertThatThrownBy(() -> client.getPaymentByOrderId(ORDER_ID))
                .isInstanceOf(TossLookupFailedException.class)
                .hasMessageContaining("HTTP_502");
    }

    @Test
    @DisplayName("응답 타임아웃이면 TossLookupFailedException")
    void throwsFailedOnTimeout() {
        next.set(new Response(200, "{}", READ_TIMEOUT.toMillis() * 3));

        assertThatThrownBy(() -> client.getPaymentByOrderId(ORDER_ID))
                .isInstanceOf(TossLookupFailedException.class);
    }

    private void respond(int status, String body) {
        next.set(new Response(status, body, 0));
    }
}
