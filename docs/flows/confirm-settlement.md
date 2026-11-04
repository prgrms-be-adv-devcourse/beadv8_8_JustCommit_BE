# 구매 확정 → 정산

> 언제 읽나: 구매 확정 · 정산 · 정산금 지급 코드를 고칠 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: 노션 기획안 「구매 확정 → 정산 시퀀스」

## 시퀀스

```mermaid
sequenceDiagram
    participant M as market
    participant K as Kafka
    participant S as settlement
    participant P as payment
    participant N as notification
    M->>M: 판매자 주문 전체 확정 · Outbox 저장 (정산 금액 > 0일 때만)
    M->>K: PurchaseConfirmed
    K->>S: 구독
    S->>S: [tx1] Settlement(PENDING) 저장 (sellerOrderId UK) · 이미 COMPLETED면 종료
    S->>P: 정산금 지급 REST (Idempotency-Key settlementId)
    P->>P: 판매자 · HOLDING · REVENUE 지갑 잠금, HOLDING 출금 → 판매자 정산금 · REVENUE 수수료
    P-->>S: PROCESSED / ALREADY_PROCESSED
    S->>S: [tx2] COMPLETED + Outbox(SettlementCompleted)
    S->>K: SettlementCompleted
    K->>N: 정산 알림
```

수수료 5% (판매액에만, 배송비 제외, 원 단위 미만 버림) → [settlement 정책](../services/settlement.md#정책)

## 배송비 매출

`ShipmentPickedUp`(Outbox) → **payment가 직접 구독**해 배송비를 매출 지갑으로 옮깁니다. 세미에서는 settlement 리스너가 했지만, MSA에서는 settlement를 거칠 이유가 없어 바꿨습니다. 멱등은 거래 내역 UK(sellerOrderId)입니다.

## 자동 구매 확정

| 항목 | 값 |
|---|---|
| 기준 | 배송 완료 후 72시간 |
| 주기 | 1시간 (market 스케줄러) |
| 건너뜀 | 환불 요청 중인 상품 |
| 이벤트 | `OrderItemAutoConfirmed` → settlement · notification |
