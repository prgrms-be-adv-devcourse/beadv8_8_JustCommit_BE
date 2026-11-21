package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.outbox.OutboxAutoConfiguration;
import com.justcommit.backend.common.messaging.outbox.OutboxRelay;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderShippingInfo;
import com.justcommit.backend.market.client.member.MemberAddress;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentDirection;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingRequest;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingResponse;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentStatusChangedEventPublisher;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import com.justcommit.backend.market.shipment.presentation.CourierWebhookRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 반품 배송 (#383): 예약 요청 방향(구매자 → 판매자)과, 예약 · 웹훅 뒤에도 배송 상태 알림 Outbox 행이 남지 않는지 확인한다.
// 발행기와 OutboxPublisher는 실제 빈이고, 커밋 뒤 Kafka 전송(OutboxRelay)만 목으로 둔다
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ShipmentService.class, ShipmentWebhookService.class,
        ShipmentStatusChangedEventPublisher.class, EventEnvelopeMapper.class})
@ImportAutoConfiguration(OutboxAutoConfiguration.class)
@TestPropertySource(properties = "outbox.scheduler.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 예약 큐와 웹훅이 직접 트랜잭션을 열고 닫는다
class ShipmentReturnIntegrationTest {

    private static final long SELLER_ORDER_ID = 7L;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @MockitoBean CourierClient courierClient;
    @MockitoBean MemberQuery memberQuery;
    @MockitoBean OrderQuery orderQuery;
    @MockitoBean OutboxRelay outboxRelay;

    @Autowired ShipmentService shipmentService;
    @Autowired ShipmentWebhookService webhookService;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void stubOrderAndSeller() {
        when(orderQuery.getShippingInfo(anyLong())).thenReturn(
                new OrderShippingInfo(9L, 10L, 20L, new BigDecimal("3000"), "구매자", "01000000000", "부산", "101호"));
        when(memberQuery.getDefaultAddress(20L))
                .thenReturn(new MemberAddress(1L, "판매자", "01012345678", "12345", "서울", null));
        when(courierClient.book(anyString(), any(CourierBookingRequest.class)))
                .thenAnswer(inv -> new CourierBookingResponse("404" + ((String) inv.getArgument(0)).substring(18),
                        inv.getArgument(0), "RESERVED", OffsetDateTime.parse("2026-11-20T10:00:00+09:00")));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table outbox_event, shipment");
    }

    @Test
    @DisplayName("반품 배송은 READY 행과 택배 주문번호만 만들고 택배사는 부르지 않는다")
    void createReturnShipmentOnlyCreatesReadyRow() {
        Long shipmentId = shipmentService.createReturnShipment(SELLER_ORDER_ID, "몬스테라");

        Shipment shipment = shipmentRepository.findById(shipmentId).orElseThrow();
        assertThat(shipment.getDirection()).isEqualTo(ShipmentDirection.RETURN);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY);
        assertThat(shipment.getCourierOrderNo()).startsWith("shipment-");
        assertThat(shipment.getItemSummary()).isEqualTo("몬스테라");
        verifyNoInteractions(courierClient);
    }

    @Test
    @DisplayName("반품 배송 예약 요청은 보내는 사람이 구매자(주문 배송지), 받는 사람이 판매자(기본 배송지)다")
    void returnBookingSendsFromBuyerToSeller() {
        Long shipmentId = shipmentService.createReturnShipment(SELLER_ORDER_ID, "몬스테라");
        String courierOrderNo = shipmentRepository.findById(shipmentId).orElseThrow().getCourierOrderNo();

        shipmentService.bookReadyShipments();

        ArgumentCaptor<CourierBookingRequest> request = ArgumentCaptor.forClass(CourierBookingRequest.class);
        verify(courierClient).book(eq(courierOrderNo), request.capture());
        assertThat(request.getValue().sender())
                .isEqualTo(new CourierBookingRequest.Party("구매자", "01000000000", "부산 101호"));
        assertThat(request.getValue().receiver())
                .isEqualTo(new CourierBookingRequest.Party("판매자", "01012345678", "서울"));
        assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.BOOKED);
    }

    @Test
    @DisplayName("반품 배송은 예약 · 수거 · 배송 완료 웹훅 뒤에도 ShipmentStatusChanged Outbox 행이 없다")
    void returnShipmentPublishesNoStatusChanged() {
        Long shipmentId = shipmentService.createReturnShipment(SELLER_ORDER_ID, "몬스테라");
        shipmentService.bookReadyShipments();
        String courierOrderNo = shipmentRepository.findById(shipmentId).orElseThrow().getCourierOrderNo();

        webhookService.receive(webhook(courierOrderNo, "PICKED_UP", "2026-11-20T11:00:00+09:00"));
        webhookService.receive(webhook(courierOrderNo, "DELIVERED", "2026-11-21T11:00:00+09:00"));

        assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(statusChangedRows()).isZero();
    }

    @Test
    @DisplayName("가는 길 배송은 예약 · 웹훅마다 ShipmentStatusChanged Outbox 행이 남는다 (반품만 빠짐)")
    void outboundShipmentStillPublishes() {
        shipmentService.createShipment(SELLER_ORDER_ID, List.of("몬스테라"));
        shipmentService.bookReadyShipments();
        String courierOrderNo = shipmentRepository.findBySellerOrderIdAndDirection(SELLER_ORDER_ID, ShipmentDirection.OUTBOUND)
                .orElseThrow().getCourierOrderNo();

        webhookService.receive(webhook(courierOrderNo, "PICKED_UP", "2026-11-20T11:00:00+09:00"));

        assertThat(statusChangedRows()).isEqualTo(2); // BOOKED, PICKED_UP
    }

    private CourierWebhookRequest webhook(String courierOrderNo, String status, String occurredAt) {
        return new CourierWebhookRequest("evt-" + status, courierOrderNo, "404000000001", status,
                OffsetDateTime.parse(occurredAt));
    }

    private Integer statusChangedRows() {
        return jdbcTemplate.queryForObject(
                "select count(*) from outbox_event where event_type = 'ShipmentStatusChanged'", Integer.class);
    }
}
