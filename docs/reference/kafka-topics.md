# Kafka 토픽 · 이벤트 표

> 언제 읽나: 어떤 이벤트를 누가 발행 · 구독하는지 정확히 찾을 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-09 · 관련: Miro 9 · 10, 노션 기획안 「Kafka 이벤트」, 노션 「정책 정리」 3.10 · 8.7

## 토픽 목록

| 토픽 | Key | 발행 |
|---|---|---|
| `member.events.v1` | `memberId` | member |
| `member.address.events.v1` | `memberId` | member |
| `market.events.v1` | `sellerOrderId` | market |
| `market.order-paid.v1` | `orderId` | market |
| `market.order-expired.v1` | `orderId` | market |
| `market.order-failed.v1` | `orderId` | market |
| `market.order-item-cancelled.v1` | `orderId` | market |
| `market.order-item-returned.v1` | `orderId` | market |
| `product.events.v1` | `productId` | product |
| `product.review.v1` | `productId` | product |
| `species.events.v1` | `speciesId` | product |
| `settlement.events.v1` | `sellerId` | settlement |

도메인 토픽 7개 + market → product 토픽 5개 = 12개. 주문 · 배송 이벤트는 `market.events.v1` 하나입니다 → [ADR 0018](../decisions/0018-market-events-single-topic.md). market → product 토픽은 이벤트마다 하나(`market.<이름>.v1`)입니다 → [ADR 0016](../decisions/0016-product-sale-events-from-market.md). 계좌는 이벤트로 복제하지 않고 payment가 member 내부 API로 조회합니다 → [ADR 0015](../decisions/0015-member-topics-two-account-api.md). 파티션 3 · concurrency 3 · 보관 7일 (🟨 제안), 복제 1. 규칙 → [이벤트 규칙](../conventions/events.md)

## 이벤트 목록

| 토픽 | 이벤트 | 구독 | 받아서 하는 일 |
|---|---|---|---|
| `member.events.v1` | `MemberCreated` · `MemberUpdated` · `MemberWithdrawn` | product · market · payment · settlement | `MEMBER_REPLICA`(닉네임 · 권한 · 판매자 여부) 저장 · 갱신 · 탈퇴 상태, `MemberCreated`면 장바구니(market) · 지갑(payment), `MemberWithdrawn`이면 상품 정리(product) |
| `member.address.events.v1` | `MemberAddressChanged` | market | **판매자 기본 배송지**(택배 보내는 사람 주소). 이름 · 연락처 · 주소는 전송 키로 암호화 |
| `market.events.v1` | `SellerOrderPaid` (판매자에게 수락 요청) | notification | "주문이 들어왔어요. 수락해 주세요" |
| `market.events.v1` | `SellerOrderAccepted` (판매자 수락, `rejectedItemCount` 포함) | notification | 구매자에게 수락 알림. `rejectedItemCount`가 1 이상이면 일부 거절 알림 1건으로 |
| `market.events.v1` | `SellerOrderCancelled` (`reason`: `BUYER` · `SELLER` · `AUTO`) · `OrderItemCancelled` | notification | `BUYER`: 판매자에게만 취소 알림 (본인 취소는 구매자에게 안 보냄). `SELLER`: 구매자에게 판매자 거절 알림. `AUTO`: 구매자에게 자동 취소 · 판매자에게 24시간 자동 거절 알림 |
| `market.events.v1` | `PurchaseConfirmed` · `OrderItemAutoConfirmed` | settlement · notification | 판매자 주문이 끝나면 정산 생성, 구매 확정 알림 |
| `market.events.v1` | 🟨 `ReturnRequested` · `ReturnApproved`(자동 승인 포함) · `ReturnRejected` · `OrderItemRefunded`(환불) | notification | 반품 신청 · 거절 · 환불 완료 알림 |
| `market.events.v1` | `ShipmentStatusChanged` | notification | 배송 상태 · 반품 수거 알림 |
| `market.order-paid.v1` | 판매 완료 (`orderId`) | product | 이 주문이 잡은 `PAYMENT_PENDING` 상품만 `SOLD_OUT`. 0건이면 정상(로그만, DLT 아님) |
| `market.order-expired.v1` · `market.order-failed.v1` | 결제 전 예약 해제 (`orderId`) | product | `PAYMENT_PENDING` → `ACTIVE`, `product_reservation`을 `RELEASED`로 (0건이어도). 만료 · 결제 확정 실패(예약 응답 타임아웃 · 지갑 없음 등) 때만, 결제 결과를 모르는 동안은 발행 안 함 |
| `market.order-item-cancelled.v1` | 판매 재개 (`orderId` · `orderItemId` · `productId` · `reason`: `BUYER` · `SELLER` · `AUTO`) | product | 환불 성공 뒤 발행. `BUYER`면 `ACTIVE`, `SELLER` · `AUTO`면 `ACTIVE` + 숨김. 주문 전체 취소도 주문상품마다 |
| `market.order-item-returned.v1` | 판매 재개 (`orderId` · `orderItemId` · `productId`) | product | 반품 환불 뒤 발행. `ACTIVE` + 숨김 |
| `product.events.v1` | 상품 변경 (상품 ID) — `ProductRegistered` · `ProductUpdated` · `ProductStatusChanged` | product(색인 리스너) | DB에서 최신 상품을 읽어 OpenSearch 문서를 덮어씀 |
| `product.events.v1` | `ProductApproved` | product(색인) · ai | 색인, 추천 후보 추가 |
| `product.events.v1` | `ProductRejected` | notification | 판매자에게 반려 사유와 함께 알림 |
| `product.review.v1` | `ProductReviewRequested` (`review_version` 포함) | product | 사진 검수 (비동기) |
| `species.events.v1` | `SpeciesProfileUpdated` | ai | 품종 임베딩 → pgvector, speciesId upsert |
| `settlement.events.v1` | `SettlementCompleted` | notification | 정산 알림 |

`ShipmentPickedUp`은 만들지 않습니다. 배송비 이동이 정산 지급 때로 바뀌어 받는 곳이 없고, 수거 알림은 `ShipmentStatusChanged`로 보냅니다 (노션 8.7). market → product 토픽은 서로 달라 도착 순서가 바뀔 수 있으므로, product는 "이 주문이 잡은 상품만, 바뀐 상품 0건이면 정상"으로 순서와 상관없이 처리합니다 → [product 정책](../services/product.md#정책)

⬜ 이벤트별 payload 필드는 각 발행 서비스 담당이 채웁니다 (#227).
