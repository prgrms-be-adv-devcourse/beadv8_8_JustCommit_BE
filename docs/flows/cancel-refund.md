# 취소 · 환불

> 언제 읽나: 주문 취소, 반품 환불 코드를 고칠 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: 노션 기획안 「주문 취소 → 환불」 「반품 → 환불」

## 수거 전 취소

배송이 `READY` · `BOOKED`일 때만 취소할 수 있습니다 (`PICKED_UP` 이후에는 반품 환불).

```mermaid
sequenceDiagram
    participant U as 구매자
    participant M as market
    participant P as payment
    participant C as 모의 택배사
    participant PR as product
    participant K as Kafka
    participant N as notification
    U->>M: 주문 상품 취소
    M->>M: 수거 전인지 확인 · 취소중 저장
    M->>P: 상품 금액 환불 (REST, Idempotency-Key orderItemId)
    alt 환불 성공
        M->>M: 취소됨 · Outbox 저장 (한 트랜잭션)
        opt 판매자 주문의 모든 상품이 취소됨
            M->>P: 배송비 환불 (REST, Idempotency-Key sellerOrderId)
            M->>C: 택배 예약 취소 (커밋 후, 실패 시 스케줄러)
        end
        M->>PR: 상품 다시 판매 (REST, 실패 시 스케줄러)
        M->>K: OrderItemCancelled / SellerOrderCancelled
        K->>N: 판매자 알림 (구매자 본인 취소는 구매자에게 알리지 않음)
        M-->>U: 취소 완료
    else 응답 타임아웃
        M->>M: 취소중 유지 (결과 불명)
        M-->>U: 취소 처리 중
    end
```

## 반품 환불

택배 수거 이후 ~ 구매 확정 전. 환불된 상품은 **다시 판매하지 않고 판매 종료**합니다.

```mermaid
sequenceDiagram
    participant U as 구매자
    participant SL as 판매자
    participant M as market
    participant C as 모의 택배사
    participant P as payment
    participant K as Kafka
    participant N as notification
    U->>M: 환불 요청 (사유)
    M->>M: 환불 요청됨 · 자동 구매 확정 멈춤 · Outbox
    M->>K: RefundRequested
    K->>N: 판매자에게 알림
    alt 판매자 승인
        SL->>M: 환불 승인 (중복 승인은 멱등)
        M->>C: 반품 수거 접수 (커밋 후)
        C-->>M: 웹훅: 반품 수거 · 판매자 도착
        M->>P: 상품 금액 환불 (REST, Idempotency-Key orderItemId)
        M->>M: 환불됨 · Outbox
        M->>K: OrderItemRefunded
        K->>N: 구매자에게 환불 완료 알림
    else 판매자 반려
        SL->>M: 환불 반려 (사유)
        M->>M: 원래 상태 · 자동 구매 확정 다시 진행
        M->>K: RefundRejected
        K->>N: 구매자에게 반려 알림
    end
```

## 실패와 재시도

| 실패 | 처리 |
|---|---|
| 환불 응답 타임아웃 | 취소중 유지 → 스케줄러가 같은 멱등 키로 다시 요청해 확정 |
| 택배 예약 취소 실패 | 5분 스케줄러가 재시도 (S-E05) |
| 상품 다시 판매 실패 | 스케줄러가 재시도, 주문 취소는 그대로 완료 |
| 같은 취소 두 번 | 환불 1번 · 택배 취소 1번 (S-E04) |
