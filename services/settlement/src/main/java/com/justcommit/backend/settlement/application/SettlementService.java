package com.justcommit.backend.settlement.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.settlement.SettlementResult;
import com.justcommit.backend.settlement.domain.Settlement;
import com.justcommit.backend.settlement.domain.exception.SettlementErrorCode;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final Clock clock;

    // 지급이 끝난 정산만 시각이 있다. 정산이 없거나 지급 전이면 empty
    @Transactional(readOnly = true)
    public Optional<LocalDateTime> findCompletedAtBySellerOrderId(Long sellerOrderId) {
        return settlementRepository.findBySellerOrderId(sellerOrderId).map(Settlement::getCompletedAt);
    }

    // sellerBurdenAmount: 판매자 부담금. 없으면 호출하는 쪽이 0으로 넘긴다
    @Transactional
    public SettlementResult settle(Long sellerOrderId, Long sellerId, BigDecimal saleAmount, BigDecimal shippingFee,
                                   BigDecimal sellerBurdenAmount, String itemSummary) {
        validateRequest(sellerOrderId, sellerId);

        // 이미 있는 판매자 주문이면 비어 있는 배송비만 채우고 기존 결과 반환. 부담금 · 상태 · 지급 시도 칸은 건드리지 않는다
        Optional<Settlement> existing = settlementRepository.findBySellerOrderId(sellerOrderId);
        if (existing.isPresent()) {
            existing.get().updateShippingFeeIfAbsent(shippingFee);
            return toResult(existing.get());
        }

        // 정산은 PENDING으로 저장만 한다. 지급은 커밋 뒤 정산 지급 스케줄러가 한다 (next_attempt_at = now)
        Settlement settlement = settlementRepository.save(
                Settlement.createSettlement(
                        sellerOrderId,
                        sellerId,
                        saleAmount,
                        shippingFee,
                        sellerBurdenAmount,
                        now(),
                        itemSummary
                )
        );

        return toResult(settlement);
    }

    @Transactional(readOnly = true)
    public List<SettlementResult> getSettlements(Long sellerId) {
        return settlementRepository
                .findBySellerIdOrderByCreatedAtDesc(sellerId)
                .stream()
                .map(this::toResult)
                .toList();
    }

    private void validateRequest(Long sellerOrderId, Long sellerId) {
        if (sellerOrderId == null || sellerId == null) {
            throw new BusinessException(SettlementErrorCode.INVALID_REQUEST);
        }
    }

    // SettlementPayoutService와 같은 Clock에서 읽고 마이크로초로 자른다 (timestamp(6)에 저장되는 값과 같게)
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private SettlementResult toResult(Settlement settlement) {
        return new SettlementResult(
                settlement.getId(),
                settlement.getSellerOrderId(),
                settlement.getSaleAmount(),
                settlement.getFee(),
                settlement.getPayoutAmount(),
                settlement.getCompletedAt(),
                settlement.getStatus(),
                settlement.getItemSummary()
        );
    }
}
