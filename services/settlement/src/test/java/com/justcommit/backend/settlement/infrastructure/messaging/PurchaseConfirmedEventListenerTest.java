package com.justcommit.backend.settlement.infrastructure.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.settlement.application.SettlementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PurchaseConfirmedEventListenerTest {

    private final SettlementService settlementService = mock(SettlementService.class);
    private final PurchaseConfirmedEventListener listener =
            new PurchaseConfirmedEventListener(new EventEnvelopeMapper(), settlementService);

    @Test
    @DisplayName("PurchaseConfirmed 봉투를 받으면 배송비까지 넘겨 정산한다")
    void purchaseConfirmed() {
        listener.on(envelope("PurchaseConfirmed", purchaseConfirmedPayload()));

        verify(settlementService).settle(10L, 7L, new BigDecimal("30000"), new BigDecimal("3000"), "몬스테라 외 1건");
    }

    @Test
    @DisplayName("PurchaseConfirmed가 아닌 market 이벤트는 정산하지 않고 건너뛴다")
    void ignoresOtherEventTypes() {
        listener.on(envelope("ShipmentStatusChanged", JsonNodeFactory.instance.objectNode()
                .put("sellerOrderId", 10L)
                .put("status", "PICKED_UP")));
        listener.on(envelope("OrderItemAutoConfirmed", JsonNodeFactory.instance.objectNode()
                .put("sellerOrderId", 10L)));

        verifyNoInteractions(settlementService);
    }

    @Test
    @DisplayName("sellerOrderId가 없는 payload는 Jackson 예외라 공통 설정이 재시도 없이 DLT로 보낸다")
    void rejectsPayloadWithoutSellerOrderId() {
        ObjectNode payload = purchaseConfirmedPayload();
        payload.remove("sellerOrderId");

        assertThatThrownBy(() -> listener.on(envelope("PurchaseConfirmed", payload)))
                .isInstanceOf(JacksonException.class);
        verifyNoInteractions(settlementService);
    }

    @Test
    @DisplayName("옛 토픽 이벤트는 배송비 없이(null) 정산한다")
    void legacyPurchaseConfirmed() {
        listener.onLegacyPurchaseConfirmed(new PurchaseConfirmedEvent(
                10L, 7L, new BigDecimal("30000"), null, LocalDateTime.of(2026, 11, 12, 10, 0), "몬스테라"));

        verify(settlementService).settle(10L, 7L, new BigDecimal("30000"), null, "몬스테라");
    }

    private static ObjectNode purchaseConfirmedPayload() {
        return JsonNodeFactory.instance.objectNode()
                .put("sellerOrderId", 10L)
                .put("sellerId", 7L)
                .put("saleAmount", new BigDecimal("30000"))
                .put("shippingFee", new BigDecimal("3000"))
                .put("confirmedAt", "2026-11-12T10:00:00")
                .put("itemSummary", "몬스테라 외 1건");
    }

    private static EventEnvelope envelope(String eventType, ObjectNode payload) {
        return new EventEnvelope("event-1", eventType, 1, OffsetDateTime.parse("2026-11-12T10:00:00+09:00"), "10",
                payload);
    }
}
