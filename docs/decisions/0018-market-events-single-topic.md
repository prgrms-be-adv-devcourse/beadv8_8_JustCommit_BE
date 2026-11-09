# 0018. 주문 · 배송 이벤트는 market.events.v1 하나로

> 상태: ✅ 확정 · 결정일 2026-11-09 · 결정한 사람: 주문 · 알림 담당 (노션 기획안 초안 「Kafka 이벤트」)
> 대체: - · 대체됨: -

## 배경

market이 발행하는 주문 이벤트(`SellerOrderPaid` · `SellerOrderCancelled` · `PurchaseConfirmed` 등)와 배송 이벤트(`ShipmentStatusChanged`)를 `order.events.v1` · `shipment.events.v1` 두 토픽으로 나눠 두었습니다. 두 토픽은 발행 서비스(market)와 Key(`sellerOrderId`)가 같고, 노션 기획안 초안은 `market.events.v1` 하나로 적고 있어 어느 쪽으로 맞출지 정해야 했습니다.

## 결정

market의 주문 · 배송 이벤트는 `market.events.v1` 하나에 발행합니다 (Key `sellerOrderId`). market → product 토픽(`market.<이름>.v1`, Key `orderId`)은 [ADR 0016](0016-product-sale-events-from-market.md) 그대로 따로 둡니다.

## 고른 이유

발행 서비스와 Key가 같아 나눌 이유가 없고, 한 토픽 · 같은 Key면 한 판매자 주문의 주문 이벤트와 배송 이벤트가 같은 파티션에 들어가 발행 순서대로 받습니다. 토픽 이름이 발행 서비스와 같아 `{도메인}.events.v1` 규칙과도 맞습니다.

## 다른 안

| 안 | 버린 이유 |
|---|---|
| `order.events.v1` · `shipment.events.v1` 유지 | 같은 판매자 주문의 주문 · 배송 이벤트 순서가 토픽 사이에서 바뀔 수 있고, 기획안과 이름이 다름 |

## 영향

[Kafka 토픽 표](../reference/kafka-topics.md), [이벤트 규칙](../conventions/events.md), [market](../services/market.md), [notification](../services/notification.md), [settlement](../services/settlement.md)
