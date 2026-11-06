# market 서비스

> 언제 읽나: market 서비스 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-06 · 관련: Miro 5 · 13, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 장바구니 | 담기 · 빼기 · 조회, 장바구니 추천(ai 호출) |
| 주문 · 결제 | 배송비 계산 · 주문 생성 · 결제 Saga 지휘 → [주문 · 결제 흐름](../flows/order-payment.md) |
| 판매자 수락 · 거절 | 결제 후 24시간 안에 승인 또는 상품 골라 거절, 무응답이면 자동 거절. 수락해야 배송 생성 |
| 배송 | 택배 예약 · 취소 큐, 웹훅 상태 반영, 지역 구분 배송비 요금표 |
| 취소 · 반품 | 수거 전 취소, 반품 신청 · 승인 · 거절 → [취소 · 환불 흐름](../flows/cancel-refund.md) |
| 구매 확정 · 리뷰 | 직접 · 자동 확정, 판매자 주문 1건당 리뷰 1번 |

K3s에 파드 2개, 같은 market DB를 씁니다.

## 소유 테이블

| 테이블 | 설명 |
|---|---|
| `CART` | 장바구니 |
| `ORDERS` | 구매자 주문 |
| `SELLER_ORDER` | 판매자별 주문 (배송비 · 배송 단위 · `confirmedAt`) |
| `ORDERS_ITEM` | 주문 상품 (`ACCEPT_PENDING` · `NORMAL` · `CANCELLED` 등) |
| `SHIPMENT` | 배송 (방향: 가는 길 / 반품, `next_attempt_at` · `last_error`) |
| ⬜ 반품 신청 (이름 미정) | 주문상품별 반품 사유 · 상태 |
| ⬜ 배송비 요금표 (이름 미정) | 기본 + 제주 · 도서산간 요금, 도서산간 우편번호, 버전 |
| `REVIEW` | 리뷰 (`seller_order_id` UK, 소프트 삭제) |
| `REVIEW_TAG` | 리뷰 태그 5종 집계 |
| `MEMBER_REPLICA` | 회원 복제본 (판매자 주소 암호화 포함) |
| `OUTBOX` | 발행 대기 이벤트 |

## 외부 API

`cart` · `cart/items` · `cart/items/{productId}` · `cart/recommendations` · `orders/cart` · `orders/{id}/pay` · `orders/{id}/cancel` · `orders/items/{id}/cancel` · `orders/items/{id}/confirm` · `orders` · `orders/{id}` · `orders/seller` · `orders/seller/{id}` · `webhooks/courier` + ⬜ 판매자 수락 · 거절 · 반품 · 리뷰 → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

| API | 부르는 서비스 | 상태 |
|---|---|---|
| `GET /internal/v1/shipments?sellerOrderIds=` | notification (알림 문구용 배송 정보) | 🟨 제안 |

상세 → [내부 API 목록](../reference/internal-apis.md)

## 호출하는 내부 API

| 대상 | 용도 | 멱등 키 |
|---|---|---|
| product | 상품 조회 · 예약 · 예약 해제 · 판매완료 · 해제(`hidden`) | orderId / productId |
| payment | 결제(잔액 차감) · 상품 환불 · 배송비 환불 | orderId / orderItemId / sellerOrderId |
| settlement | 정산 완료 시각 조회 | 🟨 제안 |
| ai-service | 장바구니 추천 (1~2초, 실패 시 최신 상품) | – |
| 404-express | 택배 예약 · 취소 · 요금표 조회 (외부 그룹, 서킷 없음) | `courierOrderNo` |

## 발행 이벤트

| 토픽 | 이벤트 |
|---|---|
| `order.events.v1` | `SellerOrderPaid` · ⬜ 판매자 승인 · 거절 · 자동 거절 · `SellerOrderCancelled` · `OrderItemCancelled` · `PurchaseConfirmed` · `OrderItemAutoConfirmed` · `RefundRequested` · `RefundRejected` · `OrderItemRefunded` |
| `shipment.events.v1` | `ShipmentStatusChanged` · `ShipmentPickedUp`(⬜ 구독자 없음) |

상세 → [Kafka 토픽 표](../reference/kafka-topics.md)

## 구독 이벤트

| 이벤트 | 받아서 하는 일 | 멱등 처리 |
|---|---|---|
| `member.events.v1` | `MEMBER_REPLICA` 저장 · 갱신 · 탈퇴 상태, `MemberCreated`이면 장바구니 생성 | memberId upsert |
| `member.address.events.v1` | 판매자 기본 배송지 (택배 보내는 사람 주소) | memberId upsert |

## 스케줄러

결제 대기 만료(30초) · 수락 자동 거절(10분) · 반품 자동 승인(10분) · 자동 구매 확정(1시간) · 택배 예약 큐(10초) · 택배 취소 큐(10초) · 상품 호출 재시도(10초) · Saga 복구(1분) · 요금표 대사(하루 1번) · Outbox 재전송(10초) → [스케줄러 표](../reference/schedulers.md)

## 정책

노션 「정책 정리」 6 · 7 · 8절 기준입니다.

| 주제 | 규칙 |
|---|---|
| 배송비 | 판매자 주문 1건당 1회. 지역 구분 요금(기본 + 제주 · 도서산간, 크기 무시). 요금표는 market 안에 두고 계산, 404-express가 바꾸면 웹훅 → 새 요금표를 버전과 함께 저장 + 하루 1번 대사. 새 요금표는 받은 뒤 생성되는 주문부터 |
| 배송비 조작 방지 | 서버가 배송지로 직접 계산, 프론트가 본 금액(`expectedShippingFee`)과 다르면 `SHIPPING_FEE_CHANGED`. 저장한 배송비는 결제 대기 중 안 바뀜 |
| 결제 대기 | 10분. 예치금이 충분하면 바로 결제, 부족하면 결제 대기 |
| 판매자 수락 | 결제 후 `ACCEPT_PENDING`. 승인 = 남은 상품 `NORMAL` + 배송 `READY`. 거절 = 판매자 주문 › 상품 트리에서 체크한 상품만 `CANCELLED` · 환불(사유 없음), 나머지는 수락. 전부 거절이면 배송 없음 + 배송비 환불. 24시간 무응답이면 자동 거절. 마감 전 재알림 없음 |
| 배송 상태 | `READY` → `BOOKED`(화면 "픽업 대기") → `PICKED_UP` → `IN_TRANSIT` → `DELIVERED`, 취소 `CANCELLED` · `CANCEL_PENDING`. 앞으로만 감 (이전 · 같은 상태 웹훅 무시), 단계별 시각 저장 |
| 택배 예약 | 수락 후 예약 큐(10초, 포기 없음). 멱등 키 `courierOrderNo = shipment-{배송 생성일 yyyyMMdd}-{배송ID 6자리}`, 반품도 새 배송 행이라 겹치지 않음 |
| 취소 | 수락 전에는 언제든 즉시. `READY`는 바로, `BOOKED`는 택배사 취소 먼저 → [취소 · 환불](../flows/cancel-refund.md) |
| 구매 확정 | 배송 완료 후 상품별 확정, 72시간 뒤 자동. 판매자 주문이 끝나면 정산 이벤트 |
| 반품 | 배송 완료 후 72시간, 판매자 귀책만, 48시간 자동 승인, 거절이면 바로 확정 → [취소 · 환불](../flows/cancel-refund.md#반품-환불) |
| 리뷰 | 판매자 평가, 판매자 주문 1건당 1번, 구매 확정 후 3일 안. 별점 0.5~5.0 · 글 10~300자 필수 · 태그 5종 · 사진 없음. 수정 불가, 3일 안 삭제 가능(소프트 삭제, 다시 쓰기 불가). 판매자 상점에 평균 별점 · 수 · 태그 집계 |
| 택배 웹훅 | `X-Api-Key`, 모르는 주문번호 · 상태는 로그만, 택배사는 실패하면 1분 → 5분 → 30분 → 2시간 → 6시간 재전송 |

## 테스트 시나리오

S-D01 ~ S-D09 · S-E01 ~ S-E10 · S-F01 ~ S-F06 · S-G02 → [테스트 시나리오](../testing/scenarios.md)
