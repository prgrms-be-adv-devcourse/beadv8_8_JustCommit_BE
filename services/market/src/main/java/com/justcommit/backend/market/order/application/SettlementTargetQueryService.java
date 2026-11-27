package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.order.domain.ReturnRequestStatus;
import com.justcommit.backend.market.order.domain.SellerOrderSettlementAmounts;
import com.justcommit.backend.market.order.infrastructure.ReturnRequestRepository;
import com.justcommit.backend.market.order.infrastructure.ReturnShippingFeeSum;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import com.justcommit.backend.market.order.infrastructure.SettlementTargetRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 정산 대상 판매자 주문 조회 (#657): settlement의 Market ↔ Settlement 대사가 쓴다.
// 정산 이벤트를 발행한(confirmedAt이 있는) 판매자 주문을 confirmedAt [from, to)로, sellerOrderId 오름차순 커서로 읽는다.
// 종류와 금액은 SellerOrderCompletion이 이벤트에 넣는 계산(SellerOrderSettlementAmounts)을 그대로 쓴다.
// 한 페이지에 쿼리 2번: 판매자 주문 페이지, 그 페이지의 반품 배송비 합
@Service
@RequiredArgsConstructor
public class SettlementTargetQueryService {

    private final SellerOrderRepository sellerOrderRepository;
    private final ReturnRequestRepository returnRequestRepository;

    @Transactional(readOnly = true)
    public SettlementTargetPage find(LocalDateTime from, LocalDateTime to, Long cursor, int size) {
        // 다음 페이지가 있는지 알려고 하나 더 읽는다
        List<SettlementTargetRow> rows = sellerOrderRepository.findSettlementTargets(
                from, to, cursor == null ? 0L : cursor, OrderItemStatus.CONFIRMED, size + 1);
        boolean hasNext = rows.size() > size;
        List<SettlementTargetRow> page = hasNext ? rows.subList(0, size) : rows;
        if (page.isEmpty()) {
            return new SettlementTargetPage(List.of(), null);
        }

        Map<Long, BigDecimal> returnShippingFees = returnRequestRepository.sumReturnShippingFeeBySellerOrderIds(
                        page.stream().map(SettlementTargetRow::sellerOrderId).toList(), ReturnRequestStatus.COMPLETED)
                .stream()
                .collect(Collectors.toMap(ReturnShippingFeeSum::sellerOrderId, ReturnShippingFeeSum::amount));

        List<SettlementTarget> targets = page.stream()
                .map(row -> new SettlementTarget(row.sellerOrderId(), row.sellerId(), row.confirmedAt(),
                        SellerOrderSettlementAmounts.of(row.hasConfirmed(), row.settlementBaseAmount(), row.shippingFee(),
                                returnShippingFees.get(row.sellerOrderId()))))
                .toList();
        Long nextCursor = hasNext ? page.getLast().sellerOrderId() : null;
        return new SettlementTargetPage(targets, nextCursor);
    }

    public record SettlementTarget(Long sellerOrderId, Long sellerId, LocalDateTime confirmedAt,
                                   SellerOrderSettlementAmounts amounts) {
    }

    // nextCursor: 다음 페이지 요청의 cursor. 마지막 페이지면 null
    public record SettlementTargetPage(List<SettlementTarget> items, Long nextCursor) {
    }
}
