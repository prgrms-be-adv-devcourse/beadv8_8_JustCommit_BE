# 0016. 상품 판매 완료 · 예약 해제 · 판매 재개는 market 이벤트로

> 상태: ✅ 확정 · 결정일 2026-11-06 · 결정한 사람: 상품 · 주문 담당 (노션 「정책 정리」 3.10 · 3.11)
> 대체: 0003 · 대체됨: -

## 배경

ADR 0003은 결제 · 취소 · 환불 · 판매 완료를 market이 product에 REST로 지휘했습니다. 판매 완료 · 예약 해제 · 판매 재개는 결과를 기다릴 필요가 없는데도 REST라서, 실패하면 market에 재시도 스케줄러가 따로 필요했고, 예약 응답이 늦게 커밋되면 이미 끝난 주문에 상품이 묶일 수 있었습니다.

## 결정

market → product REST는 **상품 예약 하나만** 남깁니다. 예약은 상품마다 주문서 가격(`expectedPrice`)을 받고, 하나라도 다르면 409 `PRICE_CHANGED`로 아무것도 예약하지 않습니다. 판매 완료 · 결제 전 예약 해제 · 판매 재개는 market이 Outbox로 발행하는 이벤트(`market.<이름>.v1`, 키 `orderId`)를 product가 구독해 처리합니다. product는 orderId별 예약 기록(`product_reservation`)을 두고, `RELEASED`인 orderId의 늦은 예약은 409로 거절합니다. 결제 · 환불 · 정산 지급을 market · settlement가 REST로 지휘하는 0003의 나머지 결정은 그대로입니다.

## 고른 이유

판매 완료 · 해제는 결과를 받아야 다음 단계로 가는 호출이 아니므로 이벤트가 맞습니다. Outbox · 재시도 · DLT를 공통 방식으로 쓰고, market의 product 재시도 스케줄러가 없어집니다. 이벤트는 토픽이 달라 순서가 바뀔 수 있으므로 product 조건(이 주문이 잡은 상품만, 0건이면 정상)을 순서와 상관없이 맞게 둡니다.

## 다른 안

| 안 | 버린 이유 |
|---|---|
| REST 유지 + market 재시도 스케줄러 (0003) | 재시도 스케줄러가 따로 필요하고, 늦게 커밋된 예약을 막을 수 없음 |
| `order.events.v1` 하나에 담기 | 키가 `sellerOrderId`라 주문(`orderId`) 단위 처리와 맞지 않음 |

## 영향

[내부 API 목록](../reference/internal-apis.md), [Kafka 토픽 표](../reference/kafka-topics.md), [market](../services/market.md), [product](../services/product.md), [주문 · 결제](../flows/order-payment.md), [취소 · 환불](../flows/cancel-refund.md)
