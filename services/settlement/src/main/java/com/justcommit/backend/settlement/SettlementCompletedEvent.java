package com.justcommit.backend.settlement;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.settlement.domain.Settlement;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

// 정산 지급 완료 → 정산 알림 (settlement.events.v1, Key sellerId, notification, #438)
// 정산 하나당 한 번. 정산금이 음수(회수)여도 같은 이벤트로 보내고 payoutAmount 부호로 구분한다.
// 배송비는 판매자 알림에 필요 없어 싣지 않고, 회수하지 못해 미수금으로 남은 금액은 payment만 알아 싣지 않는다
public record SettlementCompletedEvent(Long settlementId, Long sellerOrderId, Long sellerId,
                                       BigDecimal saleAmount, BigDecimal fee, BigDecimal sellerBurdenAmount,
                                       BigDecimal payoutAmount, String itemSummary,
                                       LocalDateTime completedAt) implements IntegrationEvent {

    public SettlementCompletedEvent {
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(payoutAmount, "payoutAmount");
        Objects.requireNonNull(completedAt, "completedAt");
    }

    // COMPLETED 정산만 이벤트로 만든다 (지급 전 · 실패 정산은 알릴 것이 없음)
    public static SettlementCompletedEvent from(Settlement settlement) {
        if (!settlement.isCompleted()) {
            throw new IllegalStateException(
                    "지급 완료되지 않은 정산은 SettlementCompleted로 보낼 수 없습니다: settlementId="
                            + settlement.getId() + ", status=" + settlement.getStatus());
        }
        return new SettlementCompletedEvent(
                settlement.getId(),
                settlement.getSellerOrderId(),
                settlement.getSellerId(),
                settlement.getSaleAmount(),
                settlement.getFee(),
                settlement.getSellerBurdenAmount(),
                settlement.getPayoutAmount(),
                settlement.getItemSummary(),
                settlement.getCompletedAt());
    }

    @Override
    public String topic() {
        return KafkaTopics.SETTLEMENT_EVENTS;
    }

    @Override
    public String eventType() {
        return "SettlementCompleted";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerId);
    }
}
