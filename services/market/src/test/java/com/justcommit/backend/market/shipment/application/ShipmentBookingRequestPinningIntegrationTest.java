package com.justcommit.backend.market.shipment.application;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderShippingInfo;
import com.justcommit.backend.market.client.member.MemberAddress;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.courier.TestBookingRequestCodecConfig;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentStatusChangedEventPublisher;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

// #672: 택배 예약 재시도는 첫 시도 본문을 그대로 보낸다. 실제 CourierClient · DB · 가짜 택배사(WireMock)로 확인한다.
// 가짜 택배사는 404-express처럼 같은 멱등 키에 같은 본문이면 200, 다른 본문이면 409 IDEMPOTENCY_KEY_REUSED로 답한다.
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, TestBookingRequestCodecConfig.class, ShipmentService.class, CourierClient.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShipmentBookingRequestPinningIntegrationTest {

    private static final String BOOKING_PATH = "/api/v1/shipments";
    private static final WireMockServer courier = startCourier();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("custom.courier.base-url", courier::baseUrl);
        registry.add("custom.courier.api-key", () -> "test-key");
    }

    @MockitoBean MemberQuery memberQuery;
    @MockitoBean OrderQuery orderQuery;
    @MockitoBean ShipmentStatusChangedEventPublisher statusChangedEventPublisher;

    @Autowired ShipmentService shipmentService;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void stubOrder() {
        courier.resetAll();
        when(orderQuery.getShippingInfo(anyLong())).thenReturn(
                new OrderShippingInfo(9L, 10L, 20L, new BigDecimal("3000"), "구매자", "01000000000", "부산", null));
    }

    @AfterEach
    void cleanUp() {
        shipmentRepository.deleteAll();
    }

    @AfterAll
    static void stopCourier() {
        courier.stop();
    }

    @Test
    @DisplayName("[S-F15] 첫 시도 시간 초과 → 판매자 배송지 변경 → 재시도에서 첫 본문이 그대로 가고 409 없이 예약된다")
    void retryAfterTimeoutSendsFirstBodyEvenIfSellerAddressChanged() {
        Shipment saved = readyShipment(7L);
        sellerAddress("서울 강남구 처음 주소");
        // 첫 요청: 택배사는 저장했지만 응답이 6초 늦어 market은 시간 초과(5초)로 끝남
        courier.stubFor(post(urlEqualTo(BOOKING_PATH)).inScenario("idempotency").whenScenarioStateIs(STARTED)
                .willSetStateTo("STORED")
                .willReturn(booked(201).withFixedDelay(6_000)));
        // 그 뒤: 처음과 같은 본문이면 200(같은 예약), 다르면 409 (404-express의 요청 해시 비교)
        courier.stubFor(post(urlEqualTo(BOOKING_PATH)).inScenario("idempotency").whenScenarioStateIs("STORED")
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.sender.address", equalTo("서울 강남구 처음 주소")))
                .willReturn(booked(200)));
        courier.stubFor(post(urlEqualTo(BOOKING_PATH)).inScenario("idempotency").whenScenarioStateIs("STORED")
                .atPriority(5)
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":409,\"code\":\"IDEMPOTENCY_KEY_REUSED\",\"message\":\"\"}")));

        shipmentService.bookReadyShipments();
        Shipment afterTimeout = shipmentRepository.findById(saved.getId()).orElseThrow();
        String storedBody = jdbcTemplate.queryForObject("select booking_request from shipment where id = ?",
                String.class, saved.getId());

        sellerAddress("서울 서초구 바뀐 주소"); // 그사이 판매자가 기본 배송지를 바꿈
        makeDue(saved.getId());
        shipmentService.bookReadyShipments();

        assertThat(afterTimeout.getStatus()).isEqualTo(ShipmentStatus.READY);
        assertThat(storedBody).isNotBlank().doesNotContain("처음 주소"); // 평문이 아니라 암호문
        courier.verify(2, postRequestedFor(urlEqualTo(BOOKING_PATH))
                .withRequestBody(matchingJsonPath("$.sender.address", equalTo("서울 강남구 처음 주소"))));
        Shipment shipment = shipmentRepository.findById(saved.getId()).orElseThrow();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.BOOKED);
        assertThat(shipment.getBookingRequest()).isNull(); // 예약이 끝나면 판매자 주소 사본을 지움
    }

    @Test
    @DisplayName("첫 시도 전에는 본문을 저장하지 않고, 판매자 주소를 못 읽으면 저장 없이 다음에 다시 만든다")
    void noBodyStoredBeforeFirstAttempt() {
        Shipment saved = readyShipment(7L);
        when(memberQuery.getDefaultAddress(20L)).thenThrow(new IllegalStateException("기본 배송지 없음"));

        shipmentService.bookReadyShipments();

        assertThat(shipmentRepository.findById(saved.getId()).orElseThrow().getBookingRequest()).isNull();
        courier.verify(0, postRequestedFor(urlEqualTo(BOOKING_PATH)));
    }

    private void sellerAddress(String address1) {
        when(memberQuery.getDefaultAddress(20L)).thenReturn(new MemberAddress(1L, "판매자", "01012345678", "12345", address1, null));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder booked(int status) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody("""
                        {"status":%d,"message":"",
                         "data":{"trackingNo":"404000000007","courierOrderNo":"shipment-20261127-000007",
                                 "status":"RESERVED","bookedAt":"2026-11-27T10:00:00+09:00"}}
                        """.formatted(status));
    }

    private Shipment readyShipment(long sellerOrderId) {
        Shipment shipment = shipmentRepository.save(new Shipment(sellerOrderId, "몬스테라"));
        shipment.assignCourierOrderNo(LocalDate.of(2026, 11, 27));
        return shipmentRepository.save(shipment);
    }

    private void makeDue(Long shipmentId) {
        jdbcTemplate.update("update shipment set next_attempt_at = now() - interval '1 second' where id = ?", shipmentId);
    }

    private static WireMockServer startCourier() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        return server;
    }
}
