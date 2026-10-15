package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderResult;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.OrdersItemResult;
import com.justcommit.backend.market.SellerOrderResult;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingRequest;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingResponse;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import com.justcommit.backend.member.MemberAddress;
import com.justcommit.backend.member.MemberQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 결제 트랜잭션 안에서 배송을 만들 때, 택배사 예약이 커밋 뒤에만 일어나는지 실제 DB로 확인한다.
@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ShipmentService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 테스트가 직접 트랜잭션을 열고 닫는다
class ShipmentAfterCommitIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean CourierClient courierClient;
    @MockitoBean MemberQuery memberQuery;

    @Autowired ShipmentService shipmentService;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        shipmentRepository.deleteAll();
    }

    @Test
    @DisplayName("결제 트랜잭션이 커밋되면 그 뒤에 택배사에 예약하고 송장번호를 저장한다")
    void booksAfterCommit() {
        stubSellerAndCourier();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            shipmentService.createShipments(order());
            verify(courierClient, never()).book(anyString(), any()); // 커밋 전에는 택배사를 부르지 않음
        });

        assertThat(shipmentRepository.findBySellerOrderId(7L)).hasValueSatisfying(shipment -> {
            assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.BOOKED);
            assertThat(shipment.getTrackingNo()).isEqualTo("404000000001");
        });
    }

    @Test
    @DisplayName("결제 트랜잭션이 롤백되면 배송 행도 남지 않고 택배사도 부르지 않는다")
    void rollbackLeavesNothing() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            shipmentService.createShipments(order());
            status.setRollbackOnly(); // 결제 처리 중 에러로 롤백되는 상황
        });

        assertThat(shipmentRepository.findBySellerOrderId(7L)).isEmpty();
        verify(courierClient, never()).book(anyString(), any());
    }

    @Test
    @DisplayName("커밋 후 판매자 주소 조회가 실패해도 배송은 READY로 남는다")
    void sellerAddressFailureLeavesReady() {
        when(memberQuery.getDefaultAddress(20L)).thenThrow(new IllegalStateException("기본 배송지 없음"));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> shipmentService.createShipments(order()));

        assertThat(shipmentRepository.findBySellerOrderId(7L)).hasValueSatisfying(shipment -> {
            assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY);
            assertThat(shipment.getCourierOrderNo()).isNotBlank();
        });
        verify(courierClient, never()).book(anyString(), any());
    }

    private void stubSellerAndCourier() {
        when(memberQuery.getDefaultAddress(20L))
                .thenReturn(new MemberAddress(1L, "판매자", "01012345678", "12345", "서울", null));
        when(courierClient.book(anyString(), any(CourierBookingRequest.class)))
                .thenReturn(new CourierBookingResponse("404000000001", "shipment-20261015-000001", "RESERVED",
                        OffsetDateTime.parse("2026-10-15T10:00:00+09:00")));
    }

    private OrderResult order() {
        SellerOrderResult sellerOrder = new SellerOrderResult(7L, 20L,
                List.of(new OrdersItemResult(11L, 101L, "몬스테라", new BigDecimal("10000"), OrderItemStatus.NORMAL)),
                new BigDecimal("10000"), null, new BigDecimal("3000"), null, null, null);
        return new OrderResult(9L, 10L, List.of(sellerOrder), new BigDecimal("13000"),
                "구매자", "01000000000", "12345", "부산", null, 1, OrderStatus.PAID);
    }
}
