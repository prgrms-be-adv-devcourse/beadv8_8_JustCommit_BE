package com.justcommit.backend.product.messaging;

import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.product.ProductUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.core.JacksonException;

import java.util.Map;

import static com.justcommit.backend.product.messaging.EventMessages.event;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MarketEventListenerTest {

    private ProductUseCase productUseCase;
    private MarketEventListener listener;

    @BeforeEach
    void setUp() {
        productUseCase = mock(ProductUseCase.class);
        listener = new MarketEventListener(new EventEnvelopeMapper(), productUseCase);
    }

    @Test
    @DisplayName("판매 완료 이벤트면 이 주문이 잡은 상품을 판매 완료로 바꾼다")
    void orderPaidMarksSoldOut() {
        listener.onOrderPaid(event("OrderPaid", 10, Map.of("orderId", 10)));

        verify(productUseCase).markSoldOut(10L);
    }

    @Test
    @DisplayName("만료 · 결제 확정 실패 이벤트면 예약을 해제한다")
    void orderReleasedReleases() {
        listener.onOrderReleased(event("OrderExpired", 11, Map.of("orderId", 11)));

        verify(productUseCase).release(11L);
    }

    @ParameterizedTest(name = "{0}이면 숨김 {1}")
    @CsvSource({"BUYER,false", "SELLER,true", "AUTO,true"})
    @DisplayName("취소 이벤트는 사유에 따라 숨김 여부를 정해 판매를 재개한다")
    void orderItemCancelledReleasesItemByReason(String reason, boolean hide) {
        listener.onOrderItemCancelled(event("OrderItemCancelled", 12,
                Map.of("orderId", 12, "orderItemId", 120, "productId", 1200, "reason", reason)));

        verify(productUseCase).releaseItem(1200L, 12L, hide);
    }

    @Test
    @DisplayName("반품 이벤트는 항상 숨기고 판매를 재개한다")
    void orderItemReturnedReleasesItemHidden() {
        listener.onOrderItemReturned(event("OrderItemReturned", 13,
                Map.of("orderId", 13, "orderItemId", 130, "productId", 1300)));

        verify(productUseCase).releaseItem(1300L, 13L, true);
    }

    @Test
    @DisplayName("필수 칸(orderId)이 없으면 payload 형식 오류(Jackson 예외, 재시도 없이 DLT)로 본다")
    void missingRequiredFieldIsMalformed() {
        assertThatThrownBy(() -> listener.onOrderPaid(event("OrderPaid", 10, Map.of("foo", 1))))
                .isInstanceOf(JacksonException.class);
        verify(productUseCase, never()).markSoldOut(any());
    }

    @Test
    @DisplayName("모르는 취소 사유면 형식 오류로 본다")
    void unknownReasonIsMalformed() {
        assertThatThrownBy(() -> listener.onOrderItemCancelled(event("OrderItemCancelled", 12,
                Map.of("orderId", 12, "orderItemId", 120, "productId", 1200, "reason", "UNKNOWN"))))
                .isInstanceOf(JacksonException.class);
        verify(productUseCase, never()).releaseItem(any(), any(), anyBoolean());
    }
}
