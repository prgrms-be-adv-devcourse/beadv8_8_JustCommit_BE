package com.justcommit.backend.payment.infrastructure.bank;

// 은행이 돌려준 지급 결과. transferId는 은행 쪽 거래 번호
public record BankTransferResult(String idempotencyKey, String transferId) {
}
