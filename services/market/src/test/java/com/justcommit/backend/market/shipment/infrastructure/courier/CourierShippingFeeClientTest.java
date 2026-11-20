package com.justcommit.backend.market.shipment.infrastructure.courier;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.justcommit.backend.market.shipment.domain.ShippingFeeTable;
import com.justcommit.backend.market.shipment.domain.ZipcodeRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourierShippingFeeClientTest {

    private static final String PATH = "/api/v1/shippingFees";

    private WireMockServer wireMock;
    private CourierShippingFeeClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        client = new CourierShippingFeeClient(wireMock.baseUrl(), "test-key");
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("택배사 요금표를 받아 요금표로 바꾸고, 키 헤더를 보낸다")
    void fetchCurrent() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"status":200,"message":"OK",
                         "data":{"version":2,"updatedAt":"2026-11-18T10:00:00+09:00",
                                 "baseFee":3000,"jejuSurcharge":3000,"remoteSurcharge":5000,
                                 "jejuZipcodes":[{"from":"63000","to":"63644","name":"제주특별자치도"}],
                                 "remoteZipcodes":[{"from":"40200","to":"40240","name":"경북 울릉도 전지역"}]}}
                        """)));

        Optional<ShippingFeeTable> table = client.fetchCurrent();

        assertThat(table).hasValueSatisfying(t -> {
            assertThat(t.getVersion()).isEqualTo(2);
            assertThat(t.getRemoteSurcharge()).isEqualByComparingTo("5000");
            assertThat(t.getRemoteZipcodes()).containsExactly(new ZipcodeRange("40200", "40240", "경북 울릉도 전지역"));
            assertThat(t.getCourierUpdatedAt()).hasToString("2026-11-18T10:00");
        });
        wireMock.verify(getRequestedFor(urlEqualTo(PATH)).withHeader("X-Api-Key", equalTo("test-key")));
    }

    @Test
    @DisplayName("택배사에 요금표가 없으면(404) 빈 값")
    void notFound() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThat(client.fetchCurrent()).isEmpty();
    }

    @Test
    @DisplayName("택배사 서버 에러는 예외로 알린다")
    void serverError() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.fetchCurrent()).isInstanceOf(RestClientException.class);
    }
}
