package com.justcommit.backend.settlement.infrastructure.messaging;

import com.justcommit.backend.market.ShipmentPickedUpEvent;
import com.justcommit.backend.payment.PaymentUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ShipmentPickedUpEventListenerTest {

    @Mock
    PaymentUseCase paymentUseCase;

    @InjectMocks
    ShipmentPickedUpEventListener listener;

    private static final Long SELLER_ORDER_ID = 200L;
    private static final Long SELLER_ID = 10L;
    private static final BigDecimal SHIPPING_FEE = new BigDecimal("3000");

    @Test
    @DisplayName("수거 완료 이벤트를 받으면 판매자 주문 ID와 배송비를 PaymentUseCase에 전달한다")
    void delegatesShippingFeeCollection() {
        ShipmentPickedUpEvent event = pickedUpEvent();

        listener.onShipmentPickedUp(event);

        verify(paymentUseCase).collectShippingFee(SELLER_ORDER_ID, SHIPPING_FEE);
    }

    @Test
    @DisplayName("동일 이벤트를 다시 수신해도 호출을 전달한다 - 중복 방지는 PaymentService의 책임이다")
    void delegatesDuplicateEventsToPaymentService() {
        ShipmentPickedUpEvent event = pickedUpEvent();

        listener.onShipmentPickedUp(event);
        listener.onShipmentPickedUp(event);

        verify(paymentUseCase, times(2)).collectShippingFee(SELLER_ORDER_ID, SHIPPING_FEE);
    }

    @Test
    @DisplayName("배송비 수집에 실패하면 예외를 삼키지 않고 전파한다")
    void propagatesCollectionFailure() {
        ShipmentPickedUpEvent event = pickedUpEvent();
        doThrow(new IllegalStateException("배송비 처리 실패"))
                .when(paymentUseCase).collectShippingFee(SELLER_ORDER_ID, SHIPPING_FEE);

        assertThatThrownBy(() -> listener.onShipmentPickedUp(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("배송비 처리 실패");
    }

    private ShipmentPickedUpEvent pickedUpEvent() {
        return new ShipmentPickedUpEvent(
                SELLER_ORDER_ID,
                SELLER_ID,
                SHIPPING_FEE,
                LocalDateTime.of(2026, 10, 16, 10, 0)
        );
    }
}
