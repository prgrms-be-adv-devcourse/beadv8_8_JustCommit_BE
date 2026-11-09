package com.justcommit.backend.market.client.settlement;

import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.client.exception.InternalApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@RequiredArgsConstructor
class SettlementClient implements SettlementQuery {

    private final SettlementApi settlementApi;

    @Override
    public SellerOrderSettlement getSettlement(Long sellerOrderId) {
        try {
            return SellerOrderSettlement.of(settlementApi.getSettledAt(sellerOrderId).data().settledAt());
        } catch (InternalApiErrorException e) {
            if (e.status() < 500) {
                throw e;
            }
            return unknown(sellerOrderId, e);
        } catch (InternalApiException | RestClientException e) {
            // RestClientException: 본문을 읽는 중 타임아웃 · 형식 오류
            return unknown(sellerOrderId, e);
        }
    }

    private static SellerOrderSettlement unknown(Long sellerOrderId, RuntimeException e) {
        log.warn("정산 조회 실패, 정산 상태를 UNKNOWN으로 둠: sellerOrderId={}", sellerOrderId, e);
        return SellerOrderSettlement.UNKNOWN;
    }
}
