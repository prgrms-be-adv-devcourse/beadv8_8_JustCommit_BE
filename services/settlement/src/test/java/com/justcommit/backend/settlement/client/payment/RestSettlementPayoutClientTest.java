package com.justcommit.backend.settlement.client.payment;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.client.InternalApiClientAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

// 정산 지급 요청을 실제 그룹 설정(internal-payment)으로 보내고, payment 자리의 WireMock이 받은 본문 · 헤더를 확인한다
class RestSettlementPayoutClientTest {

    private static final String PAYOUTS = "/internal/v1/settlementPayouts";
    private static final String INTERNAL_KEY = "test-key";

    @RegisterExtension
    static WireMockExtension payment = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @ParameterizedTest(name = "정산금 {0}, 배송비 {1}, 부담금 {2}")
    @CsvSource({
            "28500, 3000, 0",       // 부담금 없음
            "22500, 3000, 6000",    // 일부 반품
            "0,     3000, 28500",   // 0원
            "-2500, 0,    12000"    // 음수, 배송비 모르는 정산은 0으로 보냄
    })
    @DisplayName("요청 본문에 settlementId · sellerId · sellerOrderId · payoutAmount · fee · shippingFee · sellerBurdenAmount를 담고 Idempotency-Key는 settlementId")
    void sendsPayoutRequest(String payoutAmount, String shippingFee, String sellerBurdenAmount) {
        payment.stubFor(post(PAYOUTS).willReturn(aResponse().withStatus(200)));

        runner(payment.baseUrl()).run(context -> context.getBean(SettlementPayoutClient.class)
                .distributeSettlement(100L, 7L, 10L, new BigDecimal(payoutAmount), new BigDecimal("1500"),
                        new BigDecimal(shippingFee), new BigDecimal(sellerBurdenAmount)));

        payment.verify(1, postRequestedFor(urlPathEqualTo(PAYOUTS))
                .withHeader("Idempotency-Key", equalTo("100"))
                .withHeader("X-Internal-Key", equalTo(INTERNAL_KEY))
                .withRequestBody(equalToJson("""
                        {"settlementId":100,"sellerId":7,"sellerOrderId":10,"payoutAmount":%s,"fee":1500,
                         "shippingFee":%s,"sellerBurdenAmount":%s}
                        """.formatted(payoutAmount, shippingFee, sellerBurdenAmount))));
    }

    private static ApplicationContextRunner runner(String baseUrl) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        HttpClientAutoConfiguration.class,
                        ImperativeHttpClientAutoConfiguration.class,
                        HttpServiceClientPropertiesAutoConfiguration.class,
                        RestClientAutoConfiguration.class,
                        HttpServiceClientAutoConfiguration.class,
                        InternalApiClientAutoConfiguration.class))
                .withUserConfiguration(PaymentClientConfig.class, RestSettlementPayoutClient.class)
                .withPropertyValues(
                        "internal-api.key=" + INTERNAL_KEY,
                        "internal-api.groups=internal-payment",
                        "spring.http.serviceclient.internal-payment.base-url=" + baseUrl);
    }

    // SettlementApplication의 @ImportHttpServices와 같은 그룹
    @Configuration(proxyBeanMethods = false)
    @ImportHttpServices(group = "internal-payment", types = PaymentInternalApi.class)
    static class PaymentClientConfig {
    }
}
