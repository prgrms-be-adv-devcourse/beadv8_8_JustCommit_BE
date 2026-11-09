package com.justcommit.backend.market.client.settlement;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.justcommit.backend.market.client.InternalApiClientTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.market.client.InternalApiClientTestSupport;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SettlementClientTest {

    private static final String SETTLEMENT = "/internal/v1/settlements/sellerOrders/7";

    @RegisterExtension
    static WireMockExtension settlement = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    @DisplayName("settledAt이 있으면 COMPLETED")
    void settledIsCompleted() {
        settlement.stubFor(get(SETTLEMENT).willReturn(okJson(json("settlement/settled.json"))));

        assertSettlement(settlement.baseUrl(), "COMPLETED", LocalDateTime.of(2026, 11, 9, 10, 0));
    }

    @Test
    @DisplayName("정산 전(200 + settledAt null)이면 NOT_SETTLED")
    void notSettledYet() {
        settlement.stubFor(get(SETTLEMENT).willReturn(okJson(json("settlement/not-settled.json"))));

        assertSettlement(settlement.baseUrl(), "NOT_SETTLED", null);
    }

    @Test
    @DisplayName("5xx면 상세를 실패시키지 않고 UNKNOWN")
    void serverErrorIsUnknown() {
        settlement.stubFor(get(SETTLEMENT).willReturn(serverError()));

        assertSettlement(settlement.baseUrl(), "UNKNOWN", null);
    }

    @Test
    @DisplayName("응답 타임아웃이면 UNKNOWN")
    void timeoutIsUnknown() {
        settlement.stubFor(get(SETTLEMENT).willReturn(okJson(json("settlement/settled.json")).withFixedDelay(1000)));

        assertSettlement(settlement.baseUrl(), "UNKNOWN", null);
    }

    @Test
    @DisplayName("헤더를 받은 뒤 본문을 읽다 타임아웃이 나도 UNKNOWN")
    void bodyReadTimeoutIsUnknown() {
        settlement.stubFor(get(SETTLEMENT).willReturn(okJson(json("settlement/settled.json"))
                .withChunkedDribbleDelay(5, 1500)));

        assertSettlement(settlement.baseUrl(), "UNKNOWN", null);
    }

    @Test
    @DisplayName("연결 실패면 UNKNOWN")
    void connectionFailureIsUnknown() throws IOException {
        assertSettlement("http://localhost:" + closedPort(), "UNKNOWN", null);
    }

    @Test
    @DisplayName("4xx는 계약 오류라 UNKNOWN으로 숨기지 않는다")
    void clientErrorPropagates() {
        settlement.stubFor(get(SETTLEMENT).willReturn(aResponse().withStatus(404)));

        runner(settlement.baseUrl()).run(context -> assertThatThrownBy(() ->
                context.getBean(SettlementQuery.class).getSettlement(7L))
                .isInstanceOfSatisfying(InternalApiErrorException.class, e -> assertThat(e.status()).isEqualTo(404)));
    }

    private static void assertSettlement(String baseUrl, String status, LocalDateTime settledAt) {
        runner(baseUrl).run(context -> assertThat(context.getBean(SettlementQuery.class).getSettlement(7L))
                .isEqualTo(new SellerOrderSettlement(status, settledAt)));
    }

    private static ApplicationContextRunner runner(String baseUrl) {
        return InternalApiClientTestSupport.runner(baseUrl, SettlementClient.class);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
