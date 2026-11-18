package com.justcommit.backend.market.client.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.justcommit.backend.market.client.InternalApiClientTestSupport.INTERNAL_KEY;
import static com.justcommit.backend.market.client.InternalApiClientTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import com.justcommit.backend.market.client.InternalApiClientTestSupport;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PaymentClientTest {

    private static final String PAYMENTS = "/internal/v1/payments";
    private static final String ITEM_REFUND = "/internal/v1/refunds/orderItems/5";
    private static final String SHIPPING_FEE_REFUND = "/internal/v1/refunds/shippingFees/7";

    @RegisterExtension
    static WireMockExtension payment = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private ApplicationContextRunner runner() {
        return InternalApiClientTestSupport.runner(payment.baseUrl(), PaymentClient.class);
    }

    @Test
    @DisplayName("결제는 Idempotency-Key에 orderId를 넣고 PAID를 결제 완료로 분류한다")
    void paysWithOrderIdKey() {
        payment.stubFor(post(PAYMENTS).willReturn(okJson(json("payment/paid.json"))));

        assertThat(pay()).isEqualTo(PaymentOutcome.paid());
        payment.verify(postRequestedFor(urlPathEqualTo(PAYMENTS))
                .withHeader("Idempotency-Key", equalTo("99"))
                .withHeader("X-Internal-Key", equalTo(INTERNAL_KEY))
                .withRequestBody(equalToJson("{\"orderId\": 99, \"buyerId\": 10, \"amount\": 13000}")));
    }

    @Test
    @DisplayName("같은 orderId로 다시 결제하면 ALREADY_PAID도 결제 완료로 분류한다")
    void alreadyPaidCountsAsPaid() {
        payment.stubFor(post(PAYMENTS).willReturn(okJson(json("payment/already-paid.json"))));

        assertThat(pay()).isEqualTo(PaymentOutcome.paid());
    }

    @Test
    @DisplayName("200 응답의 잔액 부족은 결제 대기로 분류한다")
    void insufficientBalanceInBody() {
        payment.stubFor(post(PAYMENTS).willReturn(okJson(json("payment/insufficient-balance-ok.json"))));

        assertThat(pay()).isEqualTo(PaymentOutcome.insufficientBalance());
    }

    @Test
    @DisplayName("[S-D07] 잔액 부족 4xx는 다시 부르지 않고 결제 대기로 분류한다")
    void insufficientBalanceError() {
        payment.stubFor(post(PAYMENTS).willReturn(error(409, "payment/insufficient-balance.json")));

        assertThat(pay()).isEqualTo(PaymentOutcome.insufficientBalance());
        payment.verify(1, postRequestedFor(urlPathEqualTo(PAYMENTS)));
    }

    @Test
    @DisplayName("200 응답의 지갑 없음은 확정 실패로 분류한다")
    void walletNotFoundInBody() {
        payment.stubFor(post(PAYMENTS).willReturn(okJson(json("payment/wallet-not-found-ok.json"))));

        assertThat(pay()).isEqualTo(PaymentOutcome.rejected("WALLET_NOT_FOUND"));
    }

    @Test
    @DisplayName("지갑 없음 404는 확정 실패로 분류한다")
    void walletNotFoundError() {
        payment.stubFor(post(PAYMENTS).willReturn(error(404, "payment/wallet-not-found.json")));

        assertThat(pay()).isEqualTo(PaymentOutcome.rejected("WALLET_NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {400, 401, 403, 409})
    @DisplayName("[S-D07] 그 밖의 4xx(401 · 403 포함)는 다시 부르지 않고 확정 실패로 분류한다")
    void clientErrorIsRejected(int status) {
        payment.stubFor(post(PAYMENTS).willReturn(error(status, "payment/forbidden.json")));

        PaymentOutcome outcome = pay();

        assertThat(outcome.type()).isEqualTo(PaymentOutcome.Type.REJECTED);
        assertThat(outcome.reason()).isEqualTo("HTTP_" + status + "_FORBIDDEN");
        payment.verify(1, postRequestedFor(urlPathEqualTo(PAYMENTS)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {500, 503})
    @DisplayName("[S-D07] 5xx는 다시 부르지 않고 결과 불명으로 분류한다")
    void serverErrorIsUnknown(int status) {
        payment.stubFor(post(PAYMENTS).willReturn(error(status, "payment/server-error.json")));

        assertThat(pay()).isEqualTo(PaymentOutcome.unknown("HTTP_" + status + "_INTERNAL_ERROR"));
        payment.verify(1, postRequestedFor(urlPathEqualTo(PAYMENTS)));
    }

    @Test
    @DisplayName("[S-D07] 결제 응답 타임아웃은 다시 부르지 않고 결과 불명(TIMEOUT)으로 분류한다")
    void timeoutIsUnknown() {
        payment.stubFor(post(PAYMENTS).willReturn(okJson(json("payment/paid.json")).withFixedDelay(1000)));

        assertThat(pay()).isEqualTo(PaymentOutcome.unknown("TIMEOUT"));
        payment.verify(1, postRequestedFor(urlPathEqualTo(PAYMENTS)));
    }

    @Test
    @DisplayName("응답 중 끊김은 결과 불명으로 분류한다")
    void connectionResetIsUnknown() {
        payment.stubFor(post(PAYMENTS).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(pay().type()).isEqualTo(PaymentOutcome.Type.UNKNOWN);
    }

    @Test
    @DisplayName("응답 본문을 읽지 못하면 결과 불명으로 분류한다")
    void unreadableBodyIsUnknown() {
        payment.stubFor(post(PAYMENTS).willReturn(okJson("{ not json")));

        assertThat(pay()).isEqualTo(PaymentOutcome.unknown("RESPONSE_UNREADABLE"));
    }

    @Test
    @DisplayName("200 응답에 결제 결과가 아닌 상태 값이 오면 결과 불명으로 분류한다")
    void unexpectedStatusIsUnknown() {
        payment.stubFor(post(PAYMENTS).willReturn(okJson(json("payment/refunded.json"))));

        assertThat(pay()).isEqualTo(PaymentOutcome.unknown("UNEXPECTED_STATUS_REFUNDED"));
    }

    @Test
    @DisplayName("[S-D07] 연결 실패는 0.2초 뒤 1번 재시도한 뒤 요청 미도달(NOT_DELIVERED)로 분류한다")
    void connectionFailureIsNotDelivered() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        InternalApiClientTestSupport.runner("http://localhost:" + closedPort, PaymentClient.class)
                .run(context -> {
                    long started = System.nanoTime();
                    PaymentOutcome outcome = context.getBean(PaymentUseCase.class)
                            .pay(99L, 10L, new BigDecimal("13000"));

                    assertThat(outcome).isEqualTo(PaymentOutcome.notDelivered());
                    assertThat(outcome.reason()).isEqualTo("CONNECTION_FAILED");
                    // 재시도 간격 0.2초가 한 번 들어갔는지
                    assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
                });
    }

    @Test
    @DisplayName("원인 코드가 100자를 넘으면 last_error 칼럼 길이에 맞게 자른다")
    void reasonIsTruncated() {
        assertThat(PaymentOutcome.unknown("X".repeat(150)).reason()).hasSize(100);
    }

    @Test
    @DisplayName("주문상품 환불은 Idempotency-Key에 orderItemId를 넣는다")
    void refundsOrderItemWithOrderItemIdKey() {
        payment.stubFor(post(ITEM_REFUND).willReturn(okJson(json("payment/refunded.json"))));

        runner().run(context -> assertThat(context.getBean(PaymentUseCase.class)
                .refundCancelledItem(5L, 99L, 10L, new BigDecimal("10000")).status())
                .isEqualTo(PaymentStatus.REFUNDED));
        payment.verify(postRequestedFor(urlPathEqualTo(ITEM_REFUND))
                .withHeader("Idempotency-Key", equalTo("5"))
                .withRequestBody(equalToJson("{\"orderId\": 99, \"buyerId\": 10, \"amount\": 10000}")));
    }

    @Test
    @DisplayName("배송비 환불은 Idempotency-Key에 sellerOrderId를 넣고 ALREADY_REFUNDED도 성공으로 받는다")
    void refundsShippingFeeWithSellerOrderIdKey() {
        payment.stubFor(post(SHIPPING_FEE_REFUND).willReturn(okJson(json("payment/already-refunded.json"))));

        runner().run(context -> assertThat(context.getBean(PaymentUseCase.class)
                .refundShippingFee(7L, 99L, 10L, new BigDecimal("3000")).status())
                .isEqualTo(PaymentStatus.ALREADY_REFUNDED));
        payment.verify(postRequestedFor(urlPathEqualTo(SHIPPING_FEE_REFUND))
                .withHeader("Idempotency-Key", equalTo("7"))
                .withRequestBody(equalToJson("{\"orderId\": 99, \"buyerId\": 10, \"amount\": 3000}")));
    }

    @Test
    @DisplayName("환불 확정 실패(4xx)는 다시 부르지 않고 예외로 알려 취소를 롤백한다")
    void refundRejectionIsNotRetried() {
        payment.stubFor(post(ITEM_REFUND).willReturn(error(409, "payment/refund-rejected.json")));

        runner().run(context -> assertThatThrownBy(() -> context.getBean(PaymentUseCase.class)
                .refundCancelledItem(5L, 99L, 10L, new BigDecimal("10000")))
                .isInstanceOfSatisfying(InternalApiErrorException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("REFUND_NOT_ALLOWED");
                }));
        payment.verify(1, postRequestedFor(urlPathEqualTo(ITEM_REFUND)));
    }

    @Test
    @DisplayName("환불 응답 타임아웃은 결과 불명으로 알려 취소를 실패로 응답한다 (같은 키로 다시 시도)")
    void refundTimeoutIsResultUnknown() {
        payment.stubFor(post(ITEM_REFUND).willReturn(okJson(json("payment/refunded.json")).withFixedDelay(1000)));

        runner().run(context -> assertThatThrownBy(() -> context.getBean(PaymentUseCase.class)
                .refundCancelledItem(5L, 99L, 10L, new BigDecimal("10000")))
                .isInstanceOf(InternalApiResultUnknownException.class));
        payment.verify(1, postRequestedFor(urlPathEqualTo(ITEM_REFUND)));
    }

    private PaymentOutcome pay() {
        AtomicReference<PaymentOutcome> result = new AtomicReference<>();
        runner().run(context -> result.set(context.getBean(PaymentUseCase.class).pay(99L, 10L, new BigDecimal("13000"))));
        return result.get();
    }

    private static ResponseDefinitionBuilder error(int status, String body) {
        return aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(json(body));
    }
}
