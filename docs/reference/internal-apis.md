# 내부 API 목록

> 언제 읽나: 서비스 간 내부 API의 경로 · 멱등 키를 찾을 때
> 상태: 🟨 제안 · 마지막 수정 2026-11-04 · 관련: Miro 22, API 명세서 (MSA, 개인 노션)

## 목록

공통 규칙 → [서비스 간 REST 규칙](../conventions/internal-api.md). 타임아웃 → [장애 처리 값](../conventions/resilience.md)

| 제공 | API | 부르는 서비스 | 용도 | 멱등 키 | 상태 |
|---|---|---|---|---|---|
| product | `GET /internal/v1/products?ids=` | market · ai | 상품 정보 · 가격 · 판매 상태 일괄 | – | ✅ |
| product | `POST /internal/v1/products/reservations` | market | 주문 상품 예약 | orderId | ✅ |
| product | `DELETE /internal/v1/products/reservations/{orderId}` | market | 예약 해제 (결제 실패 · 만료) | orderId | ✅ |
| product | `POST /internal/v1/products/reservations/{orderId}/sold-out` | market | 판매완료 | orderId | ✅ |
| product | `POST /internal/v1/products/{productId}/release` | market | 취소된 상품 다시 판매 (환불 상품은 안 함) | productId | ✅ |
| payment | `POST /internal/v1/payments` | market | 잔액 차감 | orderId | ✅ |
| payment | `POST /internal/v1/refunds/order-items/{orderItemId}` | market | 상품 금액 환불 | orderItemId | ✅ |
| payment | `POST /internal/v1/refunds/shipping-fees/{sellerOrderId}` | market | 배송비 환불 | sellerOrderId | ✅ |
| payment | `POST /internal/v1/settlement-payouts` | settlement | 정산금 지급 | settlementId | ✅ |
| settlement | `GET /internal/v1/settlements/seller-orders/{sellerOrderId}` | market | 정산 완료 시각 | – | 🟨 |
| market | `GET /internal/v1/shipments?sellerOrderIds=` | notification | 알림 문구용 배송 정보 | – | 🟨 |
| ai | `/internal/v1/recommendations` | market | 장바구니 추천 | – | 🟨 |
| ai | ⬜ 경로 미정 | product | 의미 검색 (상품 ID · 점수) | – | 🟨 |

기본 배송지 · 회원 닉네임 조회 API는 만들지 않습니다 (회원 복제본으로 대체).
