package com.justcommit.backend.payment.infrastructure.bank;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

// 모의 은행 서버 API (https://github.com/ssem-i/bank). 그룹 bank, 내부 API 그룹이 아니라 X-Internal-Key를 보내지 않는다
// 요청 본문에 계좌번호 원문이 있으므로 요청, 응답 본문을 로그로 남기지 않는다
@HttpExchange
public interface BankApi {

    @PostExchange("/api/v1/transfers")
    BankResponse transfer(
            @RequestHeader("X-Api-Key") String apiKey,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TransferBody body);

    // accountNo는 원문이다. toString에 넣지 않는다
    record TransferBody(String bankCode, String accountNo, String accountHolder, long amount) {

        @Override
        public String toString() {
            return "TransferBody[bankCode=" + bankCode + ", amount=" + amount + "]";
        }
    }

    // 은행 응답 봉투 { status, code?, message, data? }. 200과 422의 data에 지급 기록이 온다
    record BankResponse(int status, String code, String message, TransferData data) {
    }

    // failureCode는 FAILED일 때만 (INVALID_ACCOUNT, ACCOUNT_HOLDER_MISMATCH)
    record TransferData(String transferId, String status, String failureCode) {
    }
}
