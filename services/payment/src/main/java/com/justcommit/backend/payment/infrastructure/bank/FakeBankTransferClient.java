package com.justcommit.backend.payment.infrastructure.bank;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 기본 은행 (custom.bank.mode=fake). 메모리에서 항상 성공하고, 같은 멱등 키면 처음 결과를 돌려준다
// 앱을 다시 시작하면 기록이 사라진다. 모의 은행 서버로 보내려면 custom.bank.mode=http (BankClientConfig)
@Component
@ConditionalOnProperty(prefix = "custom.bank", name = "mode", havingValue = "fake", matchIfMissing = true)
public class FakeBankTransferClient implements BankTransferClient {

    private final Map<String, BankTransferResult> results = new ConcurrentHashMap<>();

    @Override
    public BankTransferResult transfer(BankTransferRequest request) {
        return results.computeIfAbsent(request.idempotencyKey(),
                key -> new BankTransferResult(key, "FAKE-" + UUID.randomUUID()));
    }
}
