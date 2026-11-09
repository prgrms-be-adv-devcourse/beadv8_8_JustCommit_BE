# market 서비스

> 언제 읽나: market 서비스 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-09 · 관련: Miro 5 · 13, 노션 기획안 「MSA 분리 지점」

## 하는 일

market 서비스가 맡는 기능입니다. 시간 · 횟수 같은 값은 「정책」에 있습니다.

| 기능 | 내용 |
|---|---|
| 장바구니 | 담기 · 빼기 · 조회, 장바구니 추천(ai 호출) |
| 주문 · 결제 | 배송비 계산 · 주문 생성 · 결제 Saga 지휘 → [주문 · 결제 흐름](../flows/order-payment.md) |
| 판매자 수락 · 거절 | 결제 후 판매자가 수락하거나 상품을 골라 거절, 기한 안에 응답이 없으면 자동 거절. 수락해야 배송 생성 → [정책](#정책) |
| 배송 | 택배 예약 · 취소 큐, 택배사 웹훅 상태 반영, 지역 구분 배송비 요금표 |
| 취소 · 반품 | 수거 전 취소, 반품 신청 · 승인 · 거절 → [취소 · 환불 흐름](../flows/cancel-refund.md) |
| 구매 확정 · 리뷰 | 직접 · 자동 구매 확정, 판매자 평가 리뷰 → [정책](#정책) |

market은 K3s에 파드 2개로 뜨고, 두 파드가 같은 market DB를 씁니다.

## 소유 테이블

market 서비스가 소유하는 테이블입니다 (market DB).

| 테이블 | 설명 |
|---|---|
| `CART` | 장바구니 |
| `ORDERS` | 구매자 주문 |
| `SELLER_ORDER` | 판매자별 주문 (배송비 · 배송 단위 · `confirmedAt`) |
| `ORDERS_ITEM` | 주문상품 (`ACCEPT_PENDING` · `NORMAL` · `CANCELLED` 등) |
| `SHIPMENT` | 배송 (방향: 가는 길 / 반품, `next_attempt_at` · `last_error`) |
| ⬜ 반품 신청 (이름 미정) | 주문상품별 반품 사유 · 상태 |
| ⬜ 배송비 요금표 (이름 미정) | 기본 + 제주 · 도서산간 요금, 도서산간 우편번호, 버전 |
| `REVIEW` | 리뷰 (`seller_order_id` UK, 소프트 삭제) |
| `REVIEW_TAG` | 리뷰 태그 5종 집계 |
| `MEMBER_REPLICA` | 회원 복제본 (판매자 주소 암호화 포함) |
| `OUTBOX` | 발행 대기 이벤트 |

## 외부 API

market 서비스가 제공하는 외부 API입니다 (경로 앞 `/api/v1` 생략).

`cart` · `cart/items` · `cart/items/{productId}` · `cart/recommendations` · `orders/cart` · `orders/{id}/pay` · `orders/{id}/cancel` · `orders/items/{id}/cancel` · `orders/items/{id}/confirm` · `orders` · `orders/{id}` · `orders/seller` · `orders/seller/{id}` · `webhooks/courier` + ⬜ 판매자 수락 · 거절 · 반품 신청 · 반품 승인 · 거절 · 리뷰 → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

market 서비스가 다른 서비스에 제공하는 내부 API입니다.

| API | 부르는 서비스 | 상태 |
|---|---|---|
| `GET /internal/v1/shipments?sellerOrderIds=` | notification (알림 문구용 배송 정보) | 🟨 제안 |

상세 → [내부 API 목록](../reference/internal-apis.md)

## 호출하는 내부 API

market 서비스가 부르는 다른 서비스의 API입니다.

| 대상 | 용도 | 멱등 키 |
|---|---|---|
| product | 상품 조회 · 예약(`expectedPrice`). 판매 완료 · 예약 해제 · 판매 재개는 이벤트 → [ADR 0016](../decisions/0016-product-sale-events-from-market.md) | orderId |
| payment | 결제(잔액 차감) · 상품 환불 · 배송비 환불 | orderId / orderItemId / sellerOrderId |
| settlement | 정산 완료 시각 조회 (🟨 제안) | – |
| ai-service | 장바구니 추천 (🟨 1~2초, 실패 시 최신 상품) | – |
| 택배사 (404-express) | 택배 예약 · 취소 · 요금표 조회 (외부 그룹, 서킷 없음) | `courierOrderNo` |

## 발행 이벤트

market 서비스가 발행하는 Kafka 이벤트입니다 (모두 Outbox).

| 토픽 | 이벤트 |
|---|---|
| `market.events.v1` | `SellerOrderPaid` · `SellerOrderAccepted` · `SellerOrderRejected` · `SellerOrderCancelled` · `OrderItemCancelled` · `PurchaseConfirmed` · `OrderItemAutoConfirmed` · `ShipmentStatusChanged` · `ReturnRequested` · `ReturnApproved` · `ReturnRejected` · `OrderItemReturned` |
| `market.order-paid.v1` · `market.order-expired.v1` · `market.order-failed.v1` · `market.order-item-cancelled.v1` · `market.order-item-returned.v1` | product에 판매 완료 · 결제 전 예약 해제 · 판매 재개 (키 `orderId`) |

상세 → [Kafka 토픽 표](../reference/kafka-topics.md)

## 구독 이벤트

market 서비스가 받는 Kafka 이벤트입니다.

| 이벤트 | 받아서 하는 일 | 멱등 처리 |
|---|---|---|
| `member.events.v1` | `MEMBER_REPLICA` 저장 · 갱신 · 탈퇴 상태, `MemberCreated`이면 장바구니 생성 | memberId upsert |
| `member.address.events.v1` | 판매자 기본 배송지 (택배 보내는 사람 주소) | memberId upsert |

## 스케줄러

market 서비스의 스케줄러: 결제 대기 만료 · 수락 자동 거절 · 반품 자동 승인 · 자동 구매 확정 · 택배 예약 큐 · 택배 취소 큐 · Saga 복구 · 요금표 버전 대사 · Outbox 재전송. 주기 · 도구는 → [스케줄러 표](../reference/schedulers.md)

## 정책

market 서비스의 업무 규칙입니다. 노션 「정책 정리」 6 · 7 · 8 · 11절 기준이고, 이 표의 시간 값이 원본입니다.

| 주제 | 규칙 |
|---|---|
| 배송비 | 판매자 주문 1건당 1회. 지역 구분 요금(기본 + 제주 · 도서산간, 크기 무시). 요금표는 market 안에 두고 계산, 404-express가 바꾸면 웹훅 → 새 요금표를 버전과 함께 저장 + 버전 대사(주기 → [스케줄러 표](../reference/schedulers.md)). 새 요금표는 받은 뒤 생성되는 주문부터 |
| 배송비 조작 방지 | 서버가 배송지로 직접 계산, 프론트가 본 금액(`expectedShippingFee`)과 다르면 `SHIPPING_FEE_CHANGED`. 저장한 배송비는 결제 대기(`PAYMENT_PENDING`) 중 안 바뀜 |
| 결제 대기 (`PAYMENT_PENDING`) | 주문 생성 시각부터 10분. 예치금이 충분하면 바로 결제, 부족하면 결제 대기. 결제할 때 market이 만료 시각을 직접 확인해 지났으면 거절(`PAYMENT_WINDOW_EXPIRED`), 만료 스케줄러는 지난 주문 만료 · 예약 해제 뒷정리만. 결제 결과를 모르는 주문은 만료하지 않음 |
| 결과 불명 복구 | 결제 · 취소 · 환불 결과를 모르면 Saga 복구가 같은 멱등 키로 다시 요청 (결제 orderId · 주문상품 환불 orderItemId · 배송비 환불 sellerOrderId). 취소 · 환불은 주문상품을 「취소 중」으로 두고 환불이 확정된 뒤 취소 완료 · 판매 재개 이벤트. 5번 안 되면 `REVIEW_REQUIRED` → [주문 · 결제](../flows/order-payment.md) |
| 판매자 수락 | 결제 후 `ACCEPT_PENDING`. 수락 = 남은 주문상품 `NORMAL` + 배송 `READY`. 거절 = 판매자 주문 › 상품 트리에서 체크한 주문상품만 `CANCELLED` · 환불(사유 없음), 나머지는 수락. 전부 거절이면 배송 없음 + 배송비 환불. 결제 완료 시각(재결제면 재결제 완료 시각)부터 24시간 무응답이면 남은 주문상품 모두 자동 거절. 마감 전 재알림 없음. ⬜ 수락과 자동 거절이 겹칠 때 처리 → [미정](../open-questions.md) |
| 배송 상태 | `READY` → `BOOKED`(화면 "픽업 대기") → `PICKED_UP` → `IN_TRANSIT` → `DELIVERED`, 취소 `CANCELLED` · `CANCEL_PENDING`. 앞으로만 감 (이전 · 같은 상태 웹훅 무시), 단계별 시각 저장 |
| 택배 예약 | 수락 후 택배 예약 큐가 포기 없이 재시도 (주기 → [스케줄러 표](../reference/schedulers.md)). 멱등 키 `courierOrderNo = shipment-{배송 생성일 yyyyMMdd}-{배송ID 6자리}`, 반품도 새 배송 행이라 겹치지 않음. 예: `shipment-20261112-000123`. 송장번호는 택배사가 발급(404 + 9자리), 예약 응답을 놓치면 웹훅 값으로 채움. 상품 요약은 첫 상품명 또는 「첫 상품명 외 N건」(알림 · 운송장). 10분 넘게 `READY`면 팀 메신저로 한 번 (판매자 지연 알림 없음) |
| 취소 | 구매자는 판매자 수락 전에는 언제든 즉시 취소. 수락 뒤 `READY`는 바로, `BOOKED`는 택배사 취소 먼저 → [취소 · 환불](../flows/cancel-refund.md) |
| 구매 확정 | 배송 완료 후 주문상품별 확정, 배송 완료(`DELIVERED`) 시각부터 72시간 뒤 자동 구매 확정. 판매자 주문이 끝나면 정산 이벤트 |
| 반품 | 배송 완료(`DELIVERED`) 시각부터 72시간 안에 반품 신청, 판매자 귀책만. 반품 신청 시각부터 48시간 무응답이면 자동 승인, 거절이면 그 주문상품을 바로 구매 확정 → [취소 · 환불](../flows/cancel-refund.md#반품-환불) |
| 리뷰 | 판매자 평가, 판매자 주문 1건당 1번, 구매 확정(`SellerOrder.confirmedAt`) 시각부터 3일 안. 별점 0.5~5.0 · 글 10~300자 필수 · 태그 5종 · 사진 없음. 수정 불가, 리뷰 작성 시각부터 3일 안 삭제 가능(소프트 삭제, 다시 쓰기 불가). 판매자 상점에 평균 별점 · 수 · 태그 집계 |
| 택배 웹훅 | `POST /api/v1/webhooks/courier`, Gateway 경유 공개 경로(타임아웃 5초), `X-Api-Key`. 모르는 주문번호 · 상태는 로그만. 택배사는 실패하면 1분 → 5분 → 30분 → 2시간 → 6시간(최대 5회) 재전송, 4xx는 다시 보내지 않음(키 불일치 401은 바로 포기). 예약 취소가 끝나면 `CANCELLED` 웹훅, 요금표를 바꾸면 요금표 변경 웹훅 |
| 모의 택배사 (404-express) | 택배 예약 → 수거 → 배송 중 → 배송 완료 자동 진행 (기본 180 · 120 · 120초, 시연 때 `.env`로 줄임, 배포하면 기본값으로 돌아가 수동 설정). 같은 서버 내부망, 포트 외부 미개방 |
| 하지 않는 것 | 판매자 취소 기능 없음 (수락 · 거절로 대신, grilling 11/4 팀 결정) · 반품 사진 안 받음 (grilling 11/4 Q4) · 판매자 반품 검수 없음 · 단순 변심 반품 불가 · 거절 사유 안 받음 · 수락 마감 전 재알림 없음 · 크기별 배송비 없음 · 날씨 · 계절 배송 제한 없음 · 신고 · 제재 · 쿠폰 · 경매 · 가격 제안 · 찜(인기순) 없음 · 확장 화면은 판매자 상점(리뷰 · 판매 상품)만 |

## 테스트 시나리오

market 서비스 관련 시나리오 ID입니다.

S-A07 · S-D01 ~ S-D10 · S-E01 ~ S-E10 · S-F01 ~ S-F06 · S-G02 → [테스트 시나리오](../testing/scenarios.md)
