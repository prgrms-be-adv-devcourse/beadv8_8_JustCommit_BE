package com.justcommit.backend.settlement.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.PaymentUseCase;
import com.justcommit.backend.settlement.SettlementResult;
import com.justcommit.backend.settlement.SettlementUseCase;
import com.justcommit.backend.settlement.domain.Settlement;
import com.justcommit.backend.settlement.domain.exception.SettlementErrorCode;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SettlementService implements SettlementUseCase {

    private final SettlementRepository settlementRepository;
    private final PaymentUseCase paymentUseCase;

    @Override
    @Transactional
    public SettlementResult settle(Long sellerOrderId, Long sellerId, BigDecimal saleAmount) {
        validateRequest(sellerOrderId, sellerId);

        // 이미 정산된 판매자 주문이면 기존 결과 반환
        Optional<Settlement> existing = settlementRepository.findBySellerOrderId(sellerOrderId);
        if (existing.isPresent()) {
            return toResult(existing.get());
        }

        Settlement settlement = settlementRepository.save(
                Settlement.createSettlement(sellerOrderId, saleAmount, LocalDateTime.now())
        );

        paymentUseCase.depositSettlement(settlement.getId(), sellerId, settlement.getPayoutAmount());

        return toResult(settlement);
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
                settlement.getSettledAt()
        );
    }
}