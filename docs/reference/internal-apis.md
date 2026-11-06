# 내부 API 목록

> 언제 읽나: 서비스 간 내부 API의 경로 · 멱등 키를 찾을 때
> 상태: 🟨 제안 · 마지막 수정 2026-11-06 · 관련: Miro 22, API 명세서 (MSA, 개인 노션)

## 목록

공통 규칙 → [서비스 간 REST 규칙](../conventions/internal-api.md). 타임아웃 → [장애 처리 값](../conventions/resilience.md)

| 제공 | API | 부르는 서비스 | 용도 | 멱등 키 | 상태 |
|---|---|---|---|---|---|
| member | ⬜ 계좌 조회 | payment | 인출 신청 때 계좌 받기 (member가 복호화해 응답, ⬜ 전송 구간 암호화) | – | ✅ 기능 · ⬜ 경로 |
| market | ⬜ 탈퇴 확인 | member | `hasActiveOrders` · `activeBuyerOrderCount` · `activeSellerOrderCount` | – | ✅ 기능 · ⬜ 경로 |
| payment | ⬜ 탈퇴 확인 | member | `hasWithdrawalBlocker` + 차단 사유 코드 (잔액 · 진행 중 충전 · 충전 취소 · 인출 · 미수금) | – | ✅ 기능 · ⬜ 경로 |
| settlement | ⬜ 탈퇴 확인 | member | `hasWithdrawalBlocker` + 차단 사유 코드 (미지급 정산) | – | ✅ 기능 · ⬜ 경로 |
| product | `GET /internal/v1/products?ids=` | market · ai | 상품 정보 · 가격 · 판매 상태 일괄 | – | ✅ |
| product | `POST /internal/v1/products/reservations` | market | 주문 상품 예약 (`orderId`, `productIds`, 전부 아니면 전무, 409) | orderId | ✅ |
| product | `DELETE /internal/v1/products/reservations/{orderId}` | market | 예약 해제 (`PAYMENT_PENDING` → `ACTIVE`) | orderId | ✅ |
| product | `POST /internal/v1/products/reservations/{orderId}/sold-out` | market | 판매완료. 예약이 이미 해제됐으면 실패 | orderId | ✅ |
| product | `POST /internal/v1/products/{productId}/release` (`hidden`) | market | `SOLD_OUT` → `ACTIVE`. 거절 · 반품이면 `hidden=true`, 구매자 취소면 `false` | productId | ✅ |
| payment | `POST /internal/v1/payments` | market | 잔액 차감 (`HOLDING`으로) | orderId | ✅ |
| payment | `POST /internal/v1/refunds/order-items/{orderItemId}` | market | 상품 금액 환불 (취소 · 반품) | orderItemId | ✅ |
| payment | `POST /internal/v1/refunds/shipping-fees/{sellerOrderId}` | market | 배송비 환불 | sellerOrderId | ✅ |
| payment | `POST /internal/v1/settlement-payouts` | settlement | 정산 지급 (양수: 지급 · 음수: 회수 후 미수금) | settlementId | ✅ |
| settlement | `GET /internal/v1/settlements/seller-orders/{sellerOrderId}` | market | 정산 완료 시각 | – | 🟨 |
| market | `GET /internal/v1/shipments?sellerOrderIds=` | notification | 알림 문구용 배송 정보 | – | 🟨 |
| ai | `/internal/v1/recommendations` | market | 장바구니 추천 | – | 🟨 |

상품 의미 검색은 범위 밖이라 product → ai 호출이 없습니다 (의미 검색은 챗봇이 맡음). ⬜ 계좌 변경 확인 호출은 노션에서 어긋나 있습니다 → [미정](../open-questions.md)
