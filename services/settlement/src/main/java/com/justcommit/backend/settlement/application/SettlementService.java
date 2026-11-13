package com.justcommit.backend.settlement.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.settlement.client.payment.SettlementPayoutClient;
import com.justcommit.backend.settlement.SettlementResult;
import com.justcommit.backend.settlement.domain.Settlement;
import com.justcommit.backend.settlement.domain.exception.SettlementErrorCode;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final SettlementPayoutClient settlementPayoutClient;

    // 지급이 끝난 정산만 시각이 있다. 정산이 없거나 지급 전이면 empty
    @Transactional(readOnly = true)
    public Optional<LocalDateTime> findCompletedAtBySellerOrderId(Long sellerOrderId) {
        return settlementRepository.findBySellerOrderId(sellerOrderId).map(Settlement::getCompletedAt);
    }

    @Transactional
    public SettlementResult settle(Long sellerOrderId, Long sellerId, BigDecimal saleAmount, BigDecimal shippingFee,
                                   String itemSummary) {
        validateRequest(sellerOrderId, sellerId);

        // 이미 정산된 판매자 주문이면 비어 있는 배송비만 채우고 기존 결과 반환 (지급은 다시 하지 않음)
        Optional<Settlement> existing = settlementRepository.findBySellerOrderId(sellerOrderId);
        if (existing.isPresent()) {
            existing.get().updateShippingFeeIfAbsent(shippingFee);
            return toResult(existing.get());
        }

        LocalDateTime now = LocalDateTime.now();
        Settlement settlement = settlementRepository.save(
                Settlement.createSettlement(
                        sellerOrderId,
                        sellerId,
                        saleAmount,
                        shippingFee,
                        now,
                        itemSummary
                )
        );

        // TODO(#428): 지급 호출과 complete()는 정산 지급 스케줄러(#429)로 옮긴다. 그 전까지는 지금처럼 같은 트랜잭션에서 지급
        settlementPayoutClient.distributeSettlement(
                settlement.getId(),
                sellerId,
                settlement.getPayoutAmount(),
                settlement.getFee()
        );
        settlement.complete(now);

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
