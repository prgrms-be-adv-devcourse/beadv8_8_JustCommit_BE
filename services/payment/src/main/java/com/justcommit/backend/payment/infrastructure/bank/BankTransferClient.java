package com.justcommit.backend.payment.infrastructure.bank;

// 인출 지급 은행. 트랜잭션 밖에서만 부른다
// 같은 멱등 키(인출 건 ID)로 다시 요청하면 은행은 두 번 보내지 않고 처음 결과를 돌려줘야 한다
public interface BankTransferClient {

    // 지급에 실패하거나 결과를 모르면 예외를 던진다
    BankTransferResult transfer(BankTransferRequest request);
}
