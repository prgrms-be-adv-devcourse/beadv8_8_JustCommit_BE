# Kafka 토픽 · 이벤트 표

> 언제 읽나: 어떤 이벤트를 누가 발행 · 구독하는지 정확히 찾을 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-12 · 관련: Miro 9 · 10, 노션 기획안 「Kafka 이벤트」, 노션 「정책 정리」 3.10 · 8.7

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

도메인 토픽 7개 + market → product 토픽 5개 = 12개. 주문 · 배송 이벤트는 `market.events.v1` 하나입니다 → [ADR 0018](../decisions/0018-market-events-single-topic.md). market → product 토픽은 이벤트마다 하나(`market.<이름>.v1`)입니다 → [ADR 0016](../decisions/0016-product-sale-events-from-market.md). 계좌는 이벤트로 복제하지 않고 payment가 member 내부 API로 조회합니다 → [ADR 0015](../decisions/0015-member-topics-two-account-api.md). 파티션 3은 브로커 기본값 `KAFKA_NUM_PARTITIONS: 3`(`docker-compose.yml` · `docker-compose.prod.yml`)으로 적용 (✅ 확정, 자동 생성되는 새 토픽 · DLT에만 적용되고 이미 있는 토픽은 그대로). concurrency 3 · 보관 7일 (🟨 제안), 복제 1. 다음 단계(⬜ 미정): 토픽을 코드로 선언(common-messaging `NewTopic`), 운영 자동 생성 끄기. 규칙 → [이벤트 규칙](../conventions/events.md)

전환 중: market이 구매 확정을 `market.events.v1`로 옮기는 동안 settlement는 옛 토픽 `market.purchase-confirmed.v1`(봉투 없는 JSON, `shippingFee` 없음)도 함께 받습니다. 옛 토픽에 남은 메시지가 없으면 settlement의 옛 토픽 리스너를 지웁니다 (#364)

## 이벤트 목록

| 토픽 | 이벤트 | 구독 | 받아서 하는 일 |
|---|---|---|---|
| `member.events.v1` | `MemberCreated` · `MemberUpdated` · `MemberWithdrawn` | product · market · payment · settlement | `MEMBER_REPLICA`(닉네임 · 권한 · 판매자 여부) 저장 · 갱신 · 탈퇴 상태, `MemberCreated`면 장바구니(market) · 지갑(payment), `MemberWithdrawn`이면 상품 정리(product) |
| `member.address.events.v1` | `MemberAddressChanged` | market | **판매자 기본 배송지**(택배 보내는 사람 주소). 이름 · 연락처 · 주소는 전송 키로 암호화 |
| `market.events.v1` | `SellerOrderPaid` (판매자에게 수락 요청) | notification | "주문이 들어왔어요. 수락해 주세요" |
| `market.events.v1` | `SellerOrderAccepted` (판매자 수락, `rejectedItemCount` 포함) | notification | 구매자에게 수락 알림. `rejectedItemCount`가 1 이상이면 일부 거절 알림 1건으로 |
| `market.events.v1` | `SellerOrderRejected` (상품 전부 거절, `reason`: `SELLER` · `AUTO`) | notification | `SELLER`: 구매자에게 판매자 거절 알림. `AUTO`: 구매자에게 자동 취소 · 판매자에게 24시간 자동 거절 알림 |
| `market.events.v1` | `SellerOrderCancelled` · `OrderItemCancelled` (구매자 취소) | notification | 판매자에게만 취소 알림 (본인 취소는 구매자에게 안 보냄) |
| `market.events.v1` | `PurchaseConfirmed` (판매자 주문이 끝나면, 수동 · 자동 확정 모두) | settlement · notification | 정산 생성(배송비 포함), 구매 확정 알림 |
| `market.events.v1` | `OrderItemAutoConfirmed` (주문상품 단위) | notification | 자동 구매 확정 알림 (정산은 받지 않음) |
| `market.events.v1` | `ReturnRequested` · `ReturnApproved`(자동 승인 포함) · `ReturnRejected` · `OrderItemReturned`(반품 환불 뒤) | notification | 반품 신청 · 자동 승인 · 거절 · 환불 완료 알림 |
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

## payload

봉투(`EventEnvelope`)의 `payload`에 들어가는 칸입니다. 받는 쪽은 이 칸으로 자기 record를 만듭니다 (공유 jar 없음). 칸을 바꾸면 `version`을 올립니다.

### market 도메인 이벤트 (`market.events.v1`, Key `sellerOrderId`, #277)

| eventType | payload | 비고 |
|---|---|---|
| `SellerOrderPaid` | `sellerOrderId` · `sellerId` · `buyerId` (숫자), `paidAt` (시각) | |
| `SellerOrderCancelled` | `orderId` · `sellerOrderId` · `sellerId` · `buyerId` (숫자), `cancelledAt` (시각) | 판매자 주문 전부 취소 |
| `OrderItemCancelled` | `orderItemId` · `orderId` · `sellerOrderId` · `sellerId` (숫자), `productName` (문자열), `cancelledAt` (시각) | 일부 취소. product용 판매 재개(`market.order-item-cancelled.v1`)와 이름은 같고 토픽이 다름 |
| `OrderItemAutoConfirmed` | `orderItemId` · `orderId` · `sellerOrderId` · `buyerId` (숫자), `confirmedAt` (시각) | |
| `PurchaseConfirmed` | `sellerOrderId` · `sellerId` (숫자), `saleAmount` · `shippingFee` (금액), `confirmedAt` (시각), `itemSummary` (문자열) | 정산은 `saleAmount`에만 수수료, `shippingFee`는 `REVENUE`로 |
| `ShipmentStatusChanged` | `shipmentId` · `orderId` · `sellerOrderId` · `buyerId` · `sellerId` (숫자), `status` (`BOOKED` · `PICKED_UP` · `IN_TRANSIT` · `DELIVERED`), `occurredAt` (시각), `itemSummary` (문자열) | `occurredAt`은 배송 단계가 일어난 시각 |
| `SellerOrderAccepted` · `SellerOrderRejected` · `Return*` · `OrderItemReturned` | ⬜ | 판매자 수락 · 거절(#283), 반품 때 |

시각 칸은 market 서버 시간(서울)의 `LocalDateTime`입니다.

### market → product (Key `orderId`, #277)

| 토픽 | eventType | payload | 비고 |
|---|---|---|---|
| `market.order-paid.v1` | `OrderPaid` | `orderId` (숫자) | |
| `market.order-expired.v1` | `OrderExpired` | `orderId` (숫자) | |
| `market.order-failed.v1` | `OrderFailed` | `orderId` (숫자) | 결제 결과를 모르는 동안은 발행 안 함 |
| `market.order-item-cancelled.v1` | `OrderItemCancelled` | `orderId` · `orderItemId` · `productId` (숫자), `reason` (`BUYER` · `SELLER` · `AUTO`) | 환불 성공 뒤, 전체 취소도 주문상품마다. 네 칸 모두 필수 |
| `market.order-item-returned.v1` | ⬜ | ⬜ | 반품 기능 때 |

### product 상품 이벤트 (`product.events.v1`, Key `productId`, #338)

| eventType | payload | 비고 |
|---|---|---|
| `ProductRegistered` | `productId` (숫자) | 상품 등록 |
| `ProductUpdated` | `productId` (숫자) | 제목 · 가격 · 사진 · 품종 등 수정 |
| `ProductStatusChanged` | `productId` (숫자) | 예약 · 판매 완료 · 예약 해제 · 판매 재개 · 숨김 · 삭제 · 판매자 탈퇴 정리 |
| `ProductApproved` · `ProductRejected` · `ProductReviewRequested`(`product.review.v1`) | ⬜ | 사진 검수 때(#345) |

받는 쪽은 상품 ID로 DB에서 최신 상품을 다시 읽습니다 (같은 이벤트가 두 번 와도 결과가 같음). 주문 번호 · 판매자 기준으로 여러 상품을 한 번에 바꿀 때는 잠근 상품마다 보내므로, 실제로 바뀌지 않은 상품이 섞일 수 있습니다.

회원 이벤트(`member.events.v1`) payload → [member](../services/member.md)

⬜ 나머지 이벤트 payload는 각 발행 서비스 담당이 채웁니다 (#227).
