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
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.market.client.InternalApiClientTestSupport;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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
    @DisplayName("결제는 Idempotency-Key에 orderId를 넣고 PAID를 돌려준다")
    void paysWithOrderIdKey() {
        payment.stubFor(post(PAYMENTS).willReturn(okJson(json("payment/paid.json"))));

        runner().run(context -> {
            PaymentResult result = context.getBean(PaymentUseCase.class).pay(99L, 10L, new BigDecimal("13000"));

            assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
            assertThat(result.isPaid()).isTrue();
        });
        payment.verify(postRequestedFor(urlPathEqualTo(PAYMENTS))
                .withHeader("Idempotency-Key", equalTo("99"))
                .withHeader("X-Internal-Key", equalTo(INTERNAL_KEY))
                .withRequestBody(equalToJson("{\"orderId\": 99, \"buyerId\": 10, \"amount\": 13000}")));
    }

    @Test
    @DisplayName("같은 orderId로 다시 결제하면 ALREADY_PAID도 결제 완료로 본다")
    void alreadyPaidCountsAsPaid() {
        payment.stubFor(post(PAYMENTS).willReturn(okJson(json("payment/already-paid.json"))));

        runner().run(context -> assertThat(context.getBean(PaymentUseCase.class)
                .pay(99L, 10L, new BigDecimal("13000")).isPaid()).isTrue());
    }

    @Test
    @DisplayName("잔액 부족 409는 다시 부르지 않고 결제 대기 결과로 바꾼다")
    void insufficientBalanceIsNotRetried() {
        payment.stubFor(post(PAYMENTS).willReturn(error(409, "payment/insufficient-balance.json")));

        runner().run(context -> {
            PaymentResult result = context.getBean(PaymentUseCase.class).pay(99L, 10L, new BigDecimal("13000"));

            assertThat(result.status()).isEqualTo(PaymentStatus.INSUFFICIENT_BALANCE);
            assertThat(result.isPaid()).isFalse();
        });
        payment.verify(1, postRequestedFor(urlPathEqualTo(PAYMENTS)));
    }

    @Test
    @DisplayName("지갑 없음 404는 WALLET_NOT_FOUND 결과로 바꾼다")
    void walletNotFoundBecomesResult() {
        payment.stubFor(post(PAYMENTS).willReturn(error(404, "payment/wallet-not-found.json")));

        runner().run(context -> assertThat(context.getBean(PaymentUseCase.class)
                .pay(99L, 10L, new BigDecimal("13000")).status()).isEqualTo(PaymentStatus.WALLET_NOT_FOUND));
    }

    @Test
    @DisplayName("결제 응답 타임아웃은 결과 불명으로 알리고 다시 부르지 않는다")
    void paymentTimeoutIsResultUnknown() {
        payment.stubFor(post(PAYMENTS).willReturn(okJson(json("payment/paid.json")).withFixedDelay(1000)));

        runner().run(context -> assertThatThrownBy(() -> context.getBean(PaymentUseCase.class)
                .pay(99L, 10L, new BigDecimal("13000")))
                .isInstanceOf(InternalApiResultUnknownException.class));
        payment.verify(1, postRequestedFor(urlPathEqualTo(PAYMENTS)));
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

    private static ResponseDefinitionBuilder error(int status, String body) {
        return aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(json(body));
    }
}
