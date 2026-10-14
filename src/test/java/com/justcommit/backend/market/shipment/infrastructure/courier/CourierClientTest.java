package com.justcommit.backend.market.shipment.infrastructure.courier;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourierClientTest {

    private static final String BOOKING_PATH = "/api/v1/shipments";
    private static final String COURIER_ORDER_NO = "shipment-20261012-000045";

    private WireMockServer wireMock;
    private CourierClient courierClient;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort()); // 빈 포트에 가짜 택배사 띄우기
        wireMock.start();
        courierClient = new CourierClient(wireMock.baseUrl(), "test-key");
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("택배사가 201로 응답하면 송장번호와 예약 시각을 돌려주고, 키 헤더 두 개를 보낸다")
    void bookSuccess() {
        wireMock.stubFor(post(urlEqualTo(BOOKING_PATH))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":201,"message":"택배가 예약되었습니다.",
                                 "data":{"trackingNo":"404000000001",
                                         "courierOrderNo":"shipment-20261012-000045",
                                         "status":"RESERVED",
                                         "bookedAt":"2026-10-12T10:00:00+09:00"}}
                                """)));

        CourierBookingResponse response = courierClient.book(COURIER_ORDER_NO, request());

        assertThat(response.trackingNo()).isEqualTo("404000000001");
        assertThat(response.bookedAt()).isEqualTo(OffsetDateTime.parse("2026-10-12T10:00:00+09:00"));

        wireMock.verify(postRequestedFor(urlEqualTo(BOOKING_PATH))
                .withHeader("X-Api-Key", equalTo("test-key"))
                .withHeader("Idempotency-Key", equalTo(COURIER_ORDER_NO)));
    }

    @Test
    @DisplayName("택배사가 500으로 응답하면 다시 시도할 수 있는 예약 실패로 알린다")
    void bookServerErrorIsRetryable() {
        wireMock.stubFor(post(urlEqualTo(BOOKING_PATH))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":500,"code":"INTERNAL_ERROR","message":"서버 내부 오류가 발생했습니다."}
                                """)));

        assertThatThrownBy(() -> courierClient.book(COURIER_ORDER_NO, request()))
                .isInstanceOf(CourierBookingException.class)
                .satisfies(e -> assertThat(((CourierBookingException) e).isRetryable()).isTrue());
    }

    private CourierBookingRequest request() {
        return new CourierBookingRequest(
                new CourierBookingRequest.Party("김판매", "010-1111-2222", "서울시 강남구"),
                new CourierBookingRequest.Party("이구매", "010-3333-4444", "부산시 해운대구"),
                "몬스테라");
    }
}
