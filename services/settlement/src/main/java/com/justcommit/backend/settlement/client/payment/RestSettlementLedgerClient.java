package com.justcommit.backend.settlement.client.payment;

import com.justcommit.backend.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RestSettlementLedgerClient implements SettlementLedgerClient {

    private final PaymentInternalApi paymentInternalApi;

    // 응답 본문이나 data가 비면 빈 Map. 대사는 요청한 ID와 응답 키가 같지 않으면 그 배치를 건너뛴다
    @Override
    public Map<Long, List<SettlementLedgerEntry>> findLedgers(Collection<Long> settlementIds) {
        ApiResponse<PaymentInternalApi.SettlementLedgerResponse> response = paymentInternalApi.findSettlementLedgers(
                new PaymentInternalApi.SettlementLedgerRequest(List.copyOf(settlementIds)));
        if (response == null || response.data() == null || response.data().ledgers() == null) {
            return Map.of();
        }
        return response.data().ledgers();
    }
}
