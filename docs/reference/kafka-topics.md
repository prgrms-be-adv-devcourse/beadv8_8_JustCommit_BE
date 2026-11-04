# Kafka 토픽 · 이벤트 표

> 언제 읽나: 어떤 이벤트를 누가 발행 · 구독하는지 정확히 찾을 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro 9 · 10, 노션 기획안 「Kafka 이벤트」

## 토픽 목록

| 토픽 | Key | 발행 |
|---|---|---|
| `member.events.v1` | `memberId` | member |
| `member.address.events.v1` | `memberId` | member |
| `member.account.events.v1` | `memberId` | member |
| `order.events.v1` | `sellerOrderId` | market |
| `shipment.events.v1` | `sellerOrderId` | market |
| `product.events.v1` | `productId` | product |
| `product.review.v1` | `productId` | product |
| `species.events.v1` | `speciesId` | product |
| `settlement.events.v1` | `sellerId` | settlement |

모든 토픽: 파티션 3 · 컨슈머 concurrency 3 · 보관 7일 (🟨 제안), 복제 1 (브로커 1대), 각각 재시도 토픽과 `-dlt`가 붙음. 규칙 → [이벤트 규칙](../conventions/events.md)

## 이벤트 목록

| 토픽 | 이벤트 | 구독 | 받아서 하는 일 |
|---|---|---|---|
| `member.events.v1` | `MemberCreated` · `MemberUpdated` · `MemberWithdrawn` | product · market · payment · settlement | `MEMBER_REPLICA` 저장 · 갱신 · 탈퇴 처리, `MemberCreated`이면 장바구니(market) · 지갑(payment) 생성 |
| `member.address.events.v1` | `MemberAddressChanged` (등록 · 수정 · 기본 배송지 변경, 변경 종류로 구분) | market | 배송지 복제본 |
| `member.account.events.v1` | `MemberAccountChanged` (계좌 등록 · 변경, 계좌번호 암호화) | payment | 지급 계좌 복제본 |
| `order.events.v1` | `SellerOrderPaid` | notification | 판매자 알림 |
| `order.events.v1` | `SellerOrderCancelled` · `OrderItemCancelled` | notification | 판매자 취소 알림 |
| `order.events.v1` | `PurchaseConfirmed` · `OrderItemAutoConfirmed` | settlement · notification | 정산 생성, 구매 확정 알림 |
| `order.events.v1` | `RefundRequested` · `RefundRejected` · `OrderItemRefunded` | notification | 환불 요청(판매자) · 반려(구매자) · 완료(구매자) 알림 |
| `shipment.events.v1` | `ShipmentPickedUp` | payment | 배송비 매출 |
| `shipment.events.v1` | `ShipmentStatusChanged` | notification | 배송 상태 알림 |
| `product.events.v1` | `ProductRegistered` · `ProductUpdated` · `ProductStatusChanged` | product(색인 리스너) | OpenSearch 색인 |
| `product.events.v1` | `ProductApproved` | product(색인) · ai | 색인, 추천 후보 추가 |
| `product.events.v1` | `ProductRejected` | notification | 판매자에게 검수 거절 알림 |
| `product.review.v1` | `ProductReviewRequested` (사진 등록 · 변경 시) | product | 사진 검수 (비동기) |
| `species.events.v1` | `SpeciesProfileUpdated` (처음 217종 1회, 이후 바뀐 품종만) | ai | 품종 임베딩 → pgvector, speciesId upsert |
| `settlement.events.v1` | `SettlementCompleted` | notification | 정산 알림 |

⬜ 이벤트별 payload 필드는 각 발행 서비스 담당이 채웁니다 (#227). 판매완료 · 재판매는 이벤트가 아니라 market → product 내부 REST입니다.
