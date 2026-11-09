# 내부 API 목록

> 언제 읽나: 서비스 간 내부 API의 경로 · 멱등 키를 찾을 때
> 상태: 🟨 제안 · 마지막 수정 2026-11-08 · 관련: Miro 22, API 명세서 (MSA, 개인 노션), 노션 「정책 정리」 1.3 · 3.10

## 목록

공통 규칙 → [서비스 간 REST 규칙](../conventions/internal-api.md). 타임아웃 → [장애 처리 값](../conventions/resilience.md)

| 제공 | API | 부르는 서비스 | 용도 | 멱등 키 | 상태 |
|---|---|---|---|---|---|
| member | ⬜ 계좌 조회 | payment | 인출 신청 때 계좌 받기 (member가 복호화해 응답, ⬜ 전송 구간 암호화는 결제 담당과 정함) | – | ✅ 기능 · ⬜ 경로 |
| market | ⬜ 탈퇴 확인 | member | `hasActiveOrders` · `activeBuyerOrderCount` · `activeSellerOrderCount` (수락 대기 주문 포함) | – | ✅ 기능 · ⬜ 경로 |
| payment | ⬜ 탈퇴 확인 | member | `hasWithdrawalBlocker` + 차단 사유 코드 (잔액 · 진행 중 충전 · 충전 취소 · 인출 · 미수금) | – | ✅ 기능 · ⬜ 경로 |
| settlement | ⬜ 탈퇴 확인 | member | `hasWithdrawalBlocker` + 차단 사유 코드 (미지급 정산) | – | ✅ 기능 · ⬜ 경로 |
| product | `GET /internal/v1/products?ids=` | market · ai | 상품 정보 · 가격 · 판매 상태 일괄 | – | ✅ |
| product | `POST /internal/v1/products/reservations` | market | 상품 예약 (아래 표) | orderId | ✅ |
| payment | `POST /internal/v1/payments` | market | 잔액 차감 (`HOLDING`으로). 같은 orderId로 다시 오면 차감하지 않고 `ALREADY_PAID` | orderId | ✅ |
| payment | `POST /internal/v1/refunds/order-items/{orderItemId}` | market | 상품 금액 환불 (취소 · 거절 · 반품). 같은 키면 `ALREADY_REFUNDED`로 처음 결과, 확정 실패는 4xx | orderItemId | ✅ |
| payment | `POST /internal/v1/refunds/shipping-fees/{sellerOrderId}` | market | 배송비 환불. 같은 키면 `ALREADY_REFUNDED`, 확정 실패는 4xx | sellerOrderId | ✅ |
| payment | `POST /internal/v1/settlement-payouts` | settlement | 정산 지급 (양수: 지급 · 음수: 회수 후 미수금) | settlementId | ✅ |
| settlement | `GET /internal/v1/settlements/seller-orders/{sellerOrderId}` | market | 정산 완료 시각 | – | 🟨 |
| market | `GET /internal/v1/shipments?sellerOrderIds=` | notification | 알림 문구용 배송 정보 | – | 🟨 |
| ai | `/internal/v1/recommendations` | market | 장바구니 추천 | – | 🟨 |

판매 완료 · 결제 전 예약 해제 · 판매 재개는 내부 API가 아니라 market 이벤트입니다 → [Kafka 토픽 표](kafka-topics.md), [ADR 0016](../decisions/0016-product-sale-events-from-market.md). 상품 의미 검색은 범위 밖이라 product → ai 호출이 없습니다 (의미 검색은 챗봇이 맡음). 계좌 변경 때 다른 서비스 확인 호출은 없습니다 (노션 2.3).

**상품 예약** `POST /internal/v1/products/reservations` 규칙 (노션 3.10):

| 항목 | 내용 |
|---|---|
| 요청 | `orderId`, `productIds`, 상품마다 주문서 가격 `expectedPrice` |
| 성공 | 모든 상품이 구매 가능하고 가격이 모두 같을 때만 `PAYMENT_PENDING`으로 바꾸고 orderId 기록 (전부 아니면 전무) |
| 가격 다름 | 하나라도 다르면 아무것도 예약하지 않고 409 `PRICE_CHANGED` |
| 구매 불가 | 하나라도 예약할 수 없으면 아무것도 예약하지 않고 409 |
| 같은 orderId로 다시 | 이미 이 주문이 잡은 상품이면 성공 (상태 그대로) |
| 다른 orderId가 잡은 상품 | 409. "구매 가능" 조건을 건 UPDATE 한 번이라 동시에 잡아도 한 주문만 성공 |
| 해제된 orderId | `product_reservation`(orderId 기본키)이 `RELEASED`면 409. 예약 · 해제 모두 이 행을 잠그고 처리 |
| 응답 타임아웃 | market은 다시 호출하지 않고 주문을 실패로 끝냄 (`market.order-failed.v1`) |
