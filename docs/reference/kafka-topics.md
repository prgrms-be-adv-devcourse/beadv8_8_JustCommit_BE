# Kafka 토픽 · 이벤트 표

> 언제 읽나: 어떤 이벤트를 누가 발행 · 구독하는지 정확히 찾을 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-06 · 관련: Miro 9 · 10, 노션 기획안 「Kafka 이벤트」

## 토픽 목록

| 토픽 | Key | 발행 |
|---|---|---|
| `member.events.v1` | `memberId` | member |
| `member.address.events.v1` | `memberId` | member |
| `order.events.v1` | `sellerOrderId` | market |
| `shipment.events.v1` | `sellerOrderId` | market |
| `product.events.v1` | `productId` | product |
| `product.review.v1` | `productId` | product |
| `species.events.v1` | `speciesId` | product |
| `settlement.events.v1` | `sellerId` | settlement |

도메인 토픽 8개. 계좌는 이벤트로 복제하지 않고 payment가 member 내부 API로 조회합니다 → [ADR 0015](../decisions/0015-member-topics-two-account-api.md). 파티션 3 · concurrency 3 · 보관 7일 (🟨 제안), 복제 1. 규칙 → [이벤트 규칙](../conventions/events.md)

## 이벤트 목록

| 토픽 | 이벤트 | 구독 | 받아서 하는 일 |
|---|---|---|---|
| `member.events.v1` | `MemberCreated` · `MemberUpdated` · `MemberWithdrawn` | product · market · payment · settlement | `MEMBER_REPLICA`(닉네임 · 권한 · 판매자 여부) 저장 · 갱신 · 탈퇴 상태, `MemberCreated`면 장바구니(market) · 지갑(payment), `MemberWithdrawn`이면 상품 정리(product) |
| `member.address.events.v1` | `MemberAddressChanged` | market | **판매자 기본 배송지**(택배 보내는 사람 주소). 이름 · 연락처 · 주소는 전송 키로 암호화 |
| `order.events.v1` | `SellerOrderPaid` (판매자에게 수락 요청) | notification | "주문이 들어왔어요. 수락해 주세요" |
| `order.events.v1` | ⬜ 판매자 승인 · 거절 · 자동 거절 이벤트 (이름 미정) | notification | 구매자 · 판매자 알림 (거절과 자동 취소는 구분) |
| `order.events.v1` | `SellerOrderCancelled` · `OrderItemCancelled` | notification | 판매자 취소 알림 (구매자 본인 취소는 구매자에게 안 보냄) |
| `order.events.v1` | `PurchaseConfirmed` · `OrderItemAutoConfirmed` | settlement · notification | 판매자 주문이 끝나면 정산 생성, 구매 확정 알림 |
| `order.events.v1` | `RefundRequested` · `RefundRejected` · `OrderItemRefunded` (+ ⬜ 자동 승인) | notification | 반품 신청 · 거절 · 환불 완료 알림 |
| `shipment.events.v1` | `ShipmentStatusChanged` | notification | 배송 상태 · 반품 수거 알림 |
| `shipment.events.v1` | `ShipmentPickedUp` | – | ⬜ 구독자 없음 (배송비 이동이 정산 지급 때로 바뀜). 유지 여부 미정 |
| `product.events.v1` | 상품 변경 (상품 ID) — `ProductRegistered` · `ProductUpdated` · `ProductStatusChanged` | product(색인 리스너) | DB에서 최신 상품을 읽어 OpenSearch 문서를 덮어씀 |
| `product.events.v1` | `ProductApproved` | product(색인) · ai | 색인, 추천 후보 추가 |
| `product.events.v1` | `ProductRejected` | notification | 판매자에게 반려 사유와 함께 알림 |
| `product.review.v1` | `ProductReviewRequested` (`review_version` 포함) | product | 사진 검수 (비동기) |
| `species.events.v1` | `SpeciesProfileUpdated` | ai | 품종 임베딩 → pgvector, speciesId upsert |
| `settlement.events.v1` | `SettlementCompleted` | notification | 정산 알림 |

⬜ 이벤트별 payload 필드는 각 발행 서비스 담당이 채웁니다 (#227). 판매완료 · 해제는 이벤트가 아니라 market → product 내부 REST입니다.
