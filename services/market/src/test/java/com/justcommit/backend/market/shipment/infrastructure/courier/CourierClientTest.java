package com.justcommit.backend.market.shipment.infrastructure.courier;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.OffsetDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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

    // #671: 404-express 예약 응답(src/http/errors.ts)별 실패 종류
    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            "500, INTERNAL_ERROR, TEMPORARY",
            "503, '', TEMPORARY",
            "408, '', TEMPORARY",
            "429, '', TEMPORARY",
            "401, UNAUTHORIZED, CONFIG_ERROR",
            "404, RESOURCE_NOT_FOUND, CONFIG_ERROR",
            "400, INVALID_FIELD_ERROR, PERMANENT",
            "400, INVALID_IDEMPOTENCY_KEY, PERMANENT",
            "409, IDEMPOTENCY_KEY_REUSED, PERMANENT",
            "409, ORDER_ALREADY_CANCELLED, ALREADY_CANCELLED"
    })
    @DisplayName("택배사 예약 오류 응답을 상태 번호와 오류 코드로 실패 종류에 나눈다")
    void bookFailureClassified(int status, String code, CourierBookingFailure expected) {
        wireMock.stubFor(post(urlEqualTo(BOOKING_PATH))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"\"}")));

        assertThatThrownBy(() -> courierClient.book(COURIER_ORDER_NO, request()))
                .isInstanceOfSatisfying(CourierBookingException.class, e -> {
                    assertThat(e.getFailure()).isEqualTo(expected);
                    assertThat(e.isRetryable()).isEqualTo(expected.isRetryable());
                    assertThat(e.getMessage()).contains("status = " + status);
                });
    }

    @Test
    @DisplayName("오류 본문이 JSON이 아니어도 상태 번호로 나눈다 (409인데 코드를 모르면 확인 필요 쪽)")
    void bookFailureWithoutJsonBody() {
        wireMock.stubFor(post(urlEqualTo(BOOKING_PATH))
                .willReturn(aResponse().withStatus(409).withBody("conflict")));

        assertThatThrownBy(() -> courierClient.book(COURIER_ORDER_NO, request()))
                .isInstanceOfSatisfying(CourierBookingException.class,
                        e -> assertThat(e.getFailure()).isEqualTo(CourierBookingFailure.PERMANENT));
    }

    @Test
    @DisplayName("택배사 응답이 5초를 넘으면(본문 읽기 중 시간 초과 포함) 다시 시도할 수 있는 실패로 알린다")
    void bookTimeoutIsTemporary() {
        wireMock.stubFor(post(urlEqualTo(BOOKING_PATH))
                .willReturn(aResponse().withStatus(201).withFixedDelay(6_000)));

        assertThatThrownBy(() -> courierClient.book(COURIER_ORDER_NO, request()))
                .isInstanceOfSatisfying(CourierBookingException.class,
                        e -> assertThat(e.getFailure()).isEqualTo(CourierBookingFailure.TEMPORARY));
    }

    @Test
    @DisplayName("주문번호로 택배사 예약을 찾으면 송장번호를 돌려주고, 404면 예약이 없는 것으로 본다")
    void findByCourierOrderNo() {
        wireMock.stubFor(get(urlEqualTo(BOOKING_PATH + "/byOrderNo/" + COURIER_ORDER_NO))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":200,"message":"",
                                 "data":{"trackingNo":"404000000001","courierOrderNo":"shipment-20261012-000045",
                                         "status":"RESERVED","remainingDistanceMeters":3400}}
                                """)));
        wireMock.stubFor(get(urlEqualTo(BOOKING_PATH + "/byOrderNo/shipment-20261012-000046"))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":404,\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"\"}")));

        assertThat(courierClient.findByCourierOrderNo(COURIER_ORDER_NO))
                .hasValueSatisfying(view -> assertThat(view.trackingNo()).isEqualTo("404000000001"));
        assertThat(courierClient.findByCourierOrderNo("shipment-20261012-000046")).isEmpty();
    }

    @Test
    @DisplayName("주문번호 조회가 500이면 결과를 모르는 실패로 알려 취소 큐가 다시 시도하게 한다")
    void findByCourierOrderNoServerErrorThrows() {
        wireMock.stubFor(get(urlEqualTo(BOOKING_PATH + "/byOrderNo/" + COURIER_ORDER_NO))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> courierClient.findByCourierOrderNo(COURIER_ORDER_NO))
                .isInstanceOf(CourierCancelException.class);
    }

    @Test
    @DisplayName("주문번호 취소는 누른 시각을 +09:00 ISO 문자열로 보내고, 200이면 반송 여부와 함께 돌려준다")
    void cancelByCourierOrderNoSuccess() {
        wireMock.stubFor(post(urlEqualTo(BOOKING_PATH + "/byOrderNo/" + COURIER_ORDER_NO + "/cancel"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":200,"message":"취소가 인정되어 반송합니다.",
                                 "data":{"courierOrderNo":"shipment-20261012-000045","trackingNo":"404000000001",
                                         "status":"RETURNING","returning":true,
                                         "cancelledAt":"2026-11-17T10:00:05+09:00","returnedAt":null}}
                                """)));

        assertThat(courierClient.cancelByCourierOrderNo(COURIER_ORDER_NO, OffsetDateTime.parse("2026-11-17T10:00:00+09:00")))
                .hasValueSatisfying(response -> assertThat(response.returning()).isTrue());

        wireMock.verify(postRequestedFor(urlEqualTo(BOOKING_PATH + "/byOrderNo/" + COURIER_ORDER_NO + "/cancel"))
                .withHeader("X-Api-Key", equalTo("test-key"))
                .withRequestBody(equalToJson("{\"cancelRequestedAt\":\"2026-11-17T10:00:00+09:00\"}")));
    }

    @Test
    @DisplayName("주문번호 취소가 409면 취소 불가(빈 값), 500이면 결과를 모르는 실패로 알린다")
    void cancelByCourierOrderNoLockedOrFailed() {
        wireMock.stubFor(post(urlEqualTo(BOOKING_PATH + "/byOrderNo/" + COURIER_ORDER_NO + "/cancel"))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":409,\"code\":\"SHIPMENT_NOT_CANCELLABLE\",\"message\":\"\"}")));
        wireMock.stubFor(post(urlEqualTo(BOOKING_PATH + "/byOrderNo/shipment-20261012-000046/cancel"))
                .willReturn(aResponse().withStatus(500)));

        assertThat(courierClient.cancelByCourierOrderNo(COURIER_ORDER_NO, OffsetDateTime.parse("2026-11-17T10:00:00+09:00")))
                .isEmpty();
        assertThatThrownBy(() -> courierClient.cancelByCourierOrderNo("shipment-20261012-000046",
                OffsetDateTime.parse("2026-11-17T10:00:00+09:00")))
                .isInstanceOf(CourierCancelException.class);
    }

    @Test
    @DisplayName("화면용 배송 조회는 남은 거리 · 지점 · 세부 단계를 돌려준다")
    void trackSuccess() {
        wireMock.stubFor(get(urlEqualTo(BOOKING_PATH + "/byOrderNo/" + COURIER_ORDER_NO))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":200,"message":"",
                                 "data":{"trackingNo":"404000000001","courierOrderNo":"shipment-20261012-000045",
                                         "status":"NEAR_5KM","remainingDistanceMeters":4321,
                                         "locationName":"강남우체국","itemSummary":"몬스테라"}}
                                """)));

        CourierShipmentView view = courierClient.track(COURIER_ORDER_NO).orElseThrow();

        assertThat(view.status()).isEqualTo("NEAR_5KM");
        assertThat(view.remainingDistanceMeters()).isEqualTo(4321);
        assertThat(view.locationName()).isEqualTo("강남우체국");
    }

    @Test
    @DisplayName("화면용 배송 조회는 택배사 404 · 500이면 예외 없이 빈 값")
    void trackNotFoundOrServerErrorIsEmpty() {
        wireMock.stubFor(get(urlEqualTo(BOOKING_PATH + "/byOrderNo/" + COURIER_ORDER_NO))
                .willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(get(urlEqualTo(BOOKING_PATH + "/byOrderNo/shipment-20261012-000046"))
                .willReturn(aResponse().withStatus(500)));

        assertThat(courierClient.track(COURIER_ORDER_NO)).isEmpty();
        assertThat(courierClient.track("shipment-20261012-000046")).isEmpty();
    }

    @Test
    @DisplayName("화면용 배송 조회는 택배사가 2초 넘게 답하지 않으면 기다리지 않고 빈 값")
    void trackTimeoutIsEmpty() {
        wireMock.stubFor(get(urlEqualTo(BOOKING_PATH + "/byOrderNo/" + COURIER_ORDER_NO))
                .willReturn(aResponse().withStatus(200).withFixedDelay(4_000)
                        .withHeader("Content-Type", "application/json").withBody("{\"status\":200,\"data\":null}")));

        long started = System.nanoTime();
        assertThat(courierClient.track(COURIER_ORDER_NO)).isEmpty();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        // 응답 타임아웃 2초 (예약 · 취소의 5초가 아님)
        assertThat(elapsedMillis).isBetween(1_500L, 3_500L);
    }

    private CourierBookingRequest request() {
        return new CourierBookingRequest(
                new CourierBookingRequest.Party("김판매", "010-1111-2222", "서울시 강남구"),
                new CourierBookingRequest.Party("이구매", "010-3333-4444", "부산시 해운대구"),
                "몬스테라");
    }
}
