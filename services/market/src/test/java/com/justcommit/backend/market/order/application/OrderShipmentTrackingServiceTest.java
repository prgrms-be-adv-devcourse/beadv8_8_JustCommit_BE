package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.ShipmentTracking;
import com.justcommit.backend.market.ShipmentTrackingQuery;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.domain.ReturnRequest;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.ReturnRequestRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderShipmentTrackingServiceTest {

    private static final ShipmentTracking TRACKING = new ShipmentTracking("BOOKED", "404000000001",
            null, null, null, null, null, "NEAR_5KM", 4321, "강남우체국", null);

    @Mock OrdersRepository ordersRepository;
    @Mock SellerOrderRepository sellerOrderRepository;
    @Mock OrdersItemRepository ordersItemRepository;
    @Mock ReturnRequestRepository returnRequestRepository;
    @Mock ShipmentTrackingQuery shipmentTrackingQuery;
    @InjectMocks OrderShipmentTrackingService service;

    @Test
    @DisplayName("판매자는 본인 판매자 주문의 배송을 조회한다")
    void sellerGetsOwnShipment() {
        when(sellerOrderRepository.findByIdAndSellerId(7L, 20L)).thenReturn(Optional.of(mock(SellerOrder.class)));
        when(shipmentTrackingQuery.track(7L)).thenReturn(Optional.of(TRACKING));

        assertThat(service.sellerShipment(20L, 7L)).isEqualTo(TRACKING);
    }

    @Test
    @DisplayName("판매자가 남의 판매자 주문을 조회하면 404이고 택배사를 부르지 않는다")
    void sellerCannotSeeOthersShipment() {
        when(sellerOrderRepository.findByIdAndSellerId(7L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sellerShipment(99L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(OrderErrorCode.SELLER_ORDER_NOT_FOUND);
        verify(shipmentTrackingQuery, never()).track(anyLong());
    }

    @Test
    @DisplayName("구매자는 본인 주문에 속한 판매자 주문의 배송을 조회한다")
    void buyerGetsOwnShipment() {
        when(ordersRepository.findByIdAndBuyerId(9L, 10L)).thenReturn(Optional.of(mock(Orders.class)));
        when(sellerOrderRepository.findOrderIdById(7L)).thenReturn(Optional.of(9L));
        when(shipmentTrackingQuery.track(7L)).thenReturn(Optional.of(TRACKING));

        assertThat(service.buyerShipment(10L, 9L, 7L)).isEqualTo(TRACKING);
    }

    @Test
    @DisplayName("구매자가 남의 주문을 조회하면 404")
    void buyerCannotSeeOthersOrder() {
        when(ordersRepository.findByIdAndBuyerId(9L, 11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buyerShipment(11L, 9L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
        verify(shipmentTrackingQuery, never()).track(anyLong());
    }

    @Test
    @DisplayName("구매자가 본인 주문 번호에 다른 주문의 판매자 주문을 붙이면 404")
    void buyerCannotMixInOtherOrdersSellerOrder() {
        when(ordersRepository.findByIdAndBuyerId(9L, 10L)).thenReturn(Optional.of(mock(Orders.class)));
        when(sellerOrderRepository.findOrderIdById(8L)).thenReturn(Optional.of(55L));

        assertThatThrownBy(() -> service.buyerShipment(10L, 9L, 8L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(OrderErrorCode.SELLER_ORDER_NOT_FOUND);
        verify(shipmentTrackingQuery, never()).track(anyLong());
    }

    @Test
    @DisplayName("배송이 아직 없으면(수락 전) 404 SHIPMENT_TRACKING_NOT_FOUND")
    void noShipmentYet() {
        when(sellerOrderRepository.findByIdAndSellerId(7L, 20L)).thenReturn(Optional.of(mock(SellerOrder.class)));
        when(shipmentTrackingQuery.track(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sellerShipment(20L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(OrderErrorCode.SHIPMENT_TRACKING_NOT_FOUND);
    }

    // ---- 반품 택배 (#617) ----

    private ReturnRequest returnRequestWithShipment(Long shipmentId) {
        ReturnRequest returnRequest = mock(ReturnRequest.class);
        when(returnRequest.getShipmentId()).thenReturn(shipmentId);
        return returnRequest;
    }

    @Test
    @DisplayName("판매자는 본인 판매자 주문 상품의 반품 택배를 조회한다")
    void sellerGetsReturnShipment() {
        when(sellerOrderRepository.findByIdAndSellerId(8L, 20L)).thenReturn(Optional.of(mock(SellerOrder.class)));
        when(ordersItemRepository.findSellerOrderIdByItemId(80L)).thenReturn(Optional.of(8L));
        ReturnRequest returnRequest = returnRequestWithShipment(6L);
        when(returnRequestRepository.findByOrderItemId(80L)).thenReturn(Optional.of(returnRequest));
        when(shipmentTrackingQuery.trackReturn(6L)).thenReturn(Optional.of(TRACKING));

        assertThat(service.sellerReturnShipment(20L, 8L, 80L)).isEqualTo(TRACKING);
    }

    @Test
    @DisplayName("판매자가 남의 판매자 주문으로 반품 택배를 조회하면 404이고 택배사를 부르지 않는다")
    void sellerCannotSeeOthersReturnShipment() {
        when(sellerOrderRepository.findByIdAndSellerId(8L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sellerReturnShipment(99L, 8L, 80L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(OrderErrorCode.SELLER_ORDER_NOT_FOUND);
        verify(shipmentTrackingQuery, never()).trackReturn(anyLong());
    }

    @Test
    @DisplayName("판매자가 본인 판매자 주문 번호에 다른 판매자 주문의 상품을 붙이면 404")
    void sellerCannotMixInOtherSellerOrdersItem() {
        when(sellerOrderRepository.findByIdAndSellerId(8L, 20L)).thenReturn(Optional.of(mock(SellerOrder.class)));
        when(ordersItemRepository.findSellerOrderIdByItemId(81L)).thenReturn(Optional.of(55L));

        assertThatThrownBy(() -> service.sellerReturnShipment(20L, 8L, 81L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(OrderErrorCode.ORDER_ITEM_NOT_FOUND);
        verify(shipmentTrackingQuery, never()).trackReturn(anyLong());
    }

    @Test
    @DisplayName("구매자는 본인 주문 상품의 반품 택배를 조회한다")
    void buyerGetsReturnShipment() {
        when(ordersRepository.findByIdAndBuyerId(7L, 10L)).thenReturn(Optional.of(mock(Orders.class)));
        when(ordersItemRepository.findOrderIdByItemId(70L)).thenReturn(Optional.of(7L));
        ReturnRequest returnRequest = returnRequestWithShipment(4L);
        when(returnRequestRepository.findByOrderItemId(70L)).thenReturn(Optional.of(returnRequest));
        when(shipmentTrackingQuery.trackReturn(4L)).thenReturn(Optional.of(TRACKING));

        assertThat(service.buyerReturnShipment(10L, 7L, 70L)).isEqualTo(TRACKING);
    }

    @Test
    @DisplayName("구매자가 본인 주문 번호에 다른 주문의 상품을 붙이면 404")
    void buyerCannotMixInOtherOrdersItem() {
        when(ordersRepository.findByIdAndBuyerId(7L, 10L)).thenReturn(Optional.of(mock(Orders.class)));
        when(ordersItemRepository.findOrderIdByItemId(71L)).thenReturn(Optional.of(55L));

        assertThatThrownBy(() -> service.buyerReturnShipment(10L, 7L, 71L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(OrderErrorCode.ORDER_ITEM_NOT_FOUND);
        verify(shipmentTrackingQuery, never()).trackReturn(anyLong());
    }

    @Test
    @DisplayName("반품 신청이 없는 상품이면 404 SHIPMENT_TRACKING_NOT_FOUND")
    void noReturnRequest() {
        when(ordersRepository.findByIdAndBuyerId(7L, 10L)).thenReturn(Optional.of(mock(Orders.class)));
        when(ordersItemRepository.findOrderIdByItemId(70L)).thenReturn(Optional.of(7L));
        when(returnRequestRepository.findByOrderItemId(70L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buyerReturnShipment(10L, 7L, 70L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(OrderErrorCode.SHIPMENT_TRACKING_NOT_FOUND);
        verify(shipmentTrackingQuery, never()).trackReturn(anyLong());
    }

    @Test
    @DisplayName("승인 전이라 반품 배송이 아직 없으면 404 SHIPMENT_TRACKING_NOT_FOUND")
    void returnShipmentNotLinkedYet() {
        when(ordersRepository.findByIdAndBuyerId(7L, 10L)).thenReturn(Optional.of(mock(Orders.class)));
        when(ordersItemRepository.findOrderIdByItemId(70L)).thenReturn(Optional.of(7L));
        ReturnRequest returnRequest = returnRequestWithShipment(null);
        when(returnRequestRepository.findByOrderItemId(70L)).thenReturn(Optional.of(returnRequest));

        assertThatThrownBy(() -> service.buyerReturnShipment(10L, 7L, 70L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(OrderErrorCode.SHIPMENT_TRACKING_NOT_FOUND);
        verify(shipmentTrackingQuery, never()).trackReturn(anyLong());
    }
}
