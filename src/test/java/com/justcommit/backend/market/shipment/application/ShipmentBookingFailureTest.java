package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderResult;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.OrdersItemResult;
import com.justcommit.backend.market.SellerOrderResult;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingException;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingRequest;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import com.justcommit.backend.member.MemberAddress;
import com.justcommit.backend.member.MemberQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentBookingFailureTest {

    @Mock ShipmentRepository shipmentRepository;
    @Mock CourierClient courierClient;
    @Mock MemberQuery memberQuery;
    @InjectMocks ShipmentService shipmentService;

    @Test
    void retryableCourierFailureLeavesCreatedShipmentReady() {
        assertBookingFailureLeavesReady(true);
    }

    @Test
    void nonRetryableCourierFailureAlsoLeavesCreatedShipmentReady() {
        assertBookingFailureLeavesReady(false);
    }

    private void assertBookingFailureLeavesReady(boolean retryable) {
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(invocation -> {
            Shipment shipment = invocation.getArgument(0);
            ReflectionTestUtils.setField(shipment, "id", 30L);
            return shipment;
        });
        when(memberQuery.getDefaultAddress(20L))
                .thenReturn(new MemberAddress(1L, "판매자", "01012345678", "12345", "서울", null));
        when(courierClient.book(anyString(), any(CourierBookingRequest.class)))
                .thenThrow(new CourierBookingException("예약 실패", new RuntimeException("택배사 장애"), retryable));

        assertThatCode(() -> shipmentService.createShipments(order())).doesNotThrowAnyException();

        ArgumentCaptor<Shipment> savedShipments = ArgumentCaptor.forClass(Shipment.class);
        verify(shipmentRepository, times(2)).save(savedShipments.capture());
        verify(courierClient).book(anyString(), any(CourierBookingRequest.class));
        Shipment saved = savedShipments.getAllValues().getFirst();
        assertThat(saved.getSellerOrderId()).isEqualTo(7L);
        assertThat(saved.getStatus()).isEqualTo(ShipmentStatus.READY);
        assertThat(saved.getCourierOrderNo()).isNotBlank();
    }

    private OrderResult order() {
        SellerOrderResult sellerOrder = new SellerOrderResult(7L, 20L,
                List.of(new OrdersItemResult(11L, 101L, "몬스테라", new BigDecimal("10000"),
                        OrderItemStatus.NORMAL)), new BigDecimal("10000"), null, new BigDecimal("3000"),
                null, null, null);
        return new OrderResult(9L, 10L, List.of(sellerOrder), new BigDecimal("13000"),
                "구매자", "01000000000", "12345", "부산", null, 1, OrderStatus.PAID);
    }
}
