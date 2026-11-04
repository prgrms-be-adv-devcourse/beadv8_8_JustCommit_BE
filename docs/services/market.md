# market 서비스

> 언제 읽나: market 서비스 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro 5 · 13, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 장바구니 | 담기 · 빼기 · 조회, 장바구니 추천(ai 호출) |
| 주문 · 결제 | 주문 생성, 결제 Saga 지휘(product 예약 → payment 차감) → [주문 · 결제 흐름](../flows/order-payment.md) |
| 배송 | 택배 접수 · 취소 · 웹훅 상태 반영 |
| 취소 · 환불 | 수거 전 취소, 환불 요청 · 승인 · 반려 → [취소 · 환불 흐름](../flows/cancel-refund.md) |
| 구매 확정 | 직접 확정 · 배송 완료 후 자동 확정 |
| 리뷰 | 구매 확정한 상품 리뷰 · 태그 |

## 소유 테이블

| 테이블 | 설명 |
|---|---|
| `CART` | 장바구니 |
| `ORDERS` | 구매자 주문 |
| `SELLER_ORDER` | 판매자별 주문 (배송 단위) |
| `ORDERS_ITEM` | 주문 상품 |
| `SHIPMENT` | 배송 |
| `REVIEW` | 리뷰 |
| `REVIEW_TAG` | 리뷰 태그 |
| `MEMBER_REPLICA` | 회원 복제본 (배송지 포함) |
| `OUTBOX` | 발행 대기 이벤트 |

## 외부 API

`cart` · `cart/items` · `cart/items/{productId}` · `cart/recommendations` · `orders/cart` · `orders/{id}/pay` · `orders/{id}/cancel` · `orders/items/{id}/cancel` · `orders/items/{id}/confirm` · `orders` · `orders/{id}` · `orders/seller` · `orders/seller/{id}` · 환불 요청 · 승인 · 반려 · 리뷰 · `webhooks/courier` → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

| API | 부르는 서비스 | 상태 |
|---|---|---|
| `GET /internal/v1/shipments?sellerOrderIds=` | notification (알림 문구용 배송 정보) | 🟨 제안 |

상세 → [내부 API 목록](../reference/internal-apis.md)

## 호출하는 내부 API

| 대상 | 용도 | 멱등 키 |
|---|---|---|
| product | 상품 조회 · 예약 · 예약 해제 · 판매완료 · 취소 상품 재판매 | orderId / productId |
| payment | 결제(잔액 차감) · 상품 환불 · 배송비 환불 | orderId / orderItemId / sellerOrderId |
| settlement | 정산 완료 시각 조회 | 🟨 제안 |
| ai-service | 장바구니 추천 (1~2초, 실패 시 최신 · 인기 상품) | – |

## 발행 이벤트

| 토픽 | 이벤트 |
|---|---|
| `order.events.v1` | `SellerOrderPaid` · `SellerOrderCancelled` · `OrderItemCancelled` · `PurchaseConfirmed` · `OrderItemAutoConfirmed` · `RefundRequested` · `RefundRejected` · `OrderItemRefunded` |
| `shipment.events.v1` | `ShipmentPickedUp` · `ShipmentStatusChanged` |

상세 → [Kafka 토픽 표](../reference/kafka-topics.md)

## 구독 이벤트

| 이벤트 | 받아서 하는 일 | 멱등 처리 |
|---|---|---|
| `member.events.v1` | `MEMBER_REPLICA` 저장 · 갱신, `MemberCreated`이면 장바구니 생성 | memberId upsert |
| `member.address.events.v1` | 배송지 복제본 갱신 (주문 때 기본 배송지) | memberId upsert |

## 스케줄러

결제 대기 만료(30초) · 자동 구매 확정(1시간) · 배송 재접수(5분) · 택배 취소 재시도(5분) · Saga 복구(🟨 1분) · Outbox 재전송 → [스케줄러 표](../reference/schedulers.md)

## 정책

세미 프로젝트 정책 문서(`docs/policy.md`, 삭제됨)에서 옮긴 확정 정책입니다.

### 배송비
1. 배송비는 판매자의 기본 배송지와 구매자 배송지의 거리를 고려해 계산한다.
2. 세미 프로젝트에서는 판매자 주문당 3,000원으로 고정한다.
3. 거리 계산 API는 택배 도메인에서 구현한다.

### 결제 대기
1. 결제 대기 시간은 10분이다.
2. 예치금이 충분하면 주문과 동시에 바로 결제하고, 부족할 때만 결제 대기 상태로 둔다.
3. 장바구니에서 주문할 때 예치금이 부족하면 먼저 충전한 뒤 주문을 생성한다.
4. 주문 생성 중 예치금이 부족해지는 예외 상황에서는 결제 대기 시간(10분)을 적용해 진행한다.

### 부분 취소
1. 배송 상태가 배송 준비(`READY`) 또는 접수 완료(`BOOKED`)일 때만 부분 취소할 수 있다.
2. 택배사가 상품을 수거한(`PICKED_UP`) 이후에는 취소할 수 없다.

## 테스트 시나리오

S-D01 ~ S-D07 · S-E01 ~ S-E06 · S-F01 ~ S-F06 · S-G02 → [테스트 시나리오](../testing/scenarios.md)
