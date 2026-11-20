# Kafka 토픽 · 이벤트 표

> 언제 읽나: 어떤 이벤트를 누가 발행 · 구독하는지 정확히 찾을 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-20 · 관련: Miro 9 · 10, 노션 기획안 「Kafka 이벤트」, 노션 「정책 정리」 3.10 · 8.7

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
| `product.events.v1` | `productId` (`ImageReviewDelayed`만 고정값 `image-review-delayed`) | product |
| `product.review.v1` | `productId` | product |
| `species.events.v1` | `speciesId` | ⬜ 발행 안 함 → [ADR 0020](../decisions/0020-species-profile-ai-seed.md) |
| `settlement.events.v1` | `sellerId` | settlement |

도메인 토픽 7개 + market → product 토픽 5개 = 12개 (`species.events.v1`은 발행하지 않고, 상수 정리는 common 담당과 정함 ⬜). 주문 · 배송 이벤트는 `market.events.v1` 하나입니다 → [ADR 0018](../decisions/0018-market-events-single-topic.md). market → product 토픽은 이벤트마다 하나(`market.<이름>.v1`)입니다 → [ADR 0016](../decisions/0016-product-sale-events-from-market.md). 계좌는 이벤트로 복제하지 않고 payment가 member 내부 API로 조회합니다 → [ADR 0015](../decisions/0015-member-topics-two-account-api.md). 파티션 3은 브로커 기본값 `KAFKA_NUM_PARTITIONS: 3`(`docker-compose.yml` · `docker-compose.prod.yml`)으로 적용 (✅ 확정, 자동 생성되는 새 토픽 · DLT에만 적용되고 이미 있는 토픽은 그대로). concurrency 3 · 보관 7일 (🟨 제안), 복제 1. 다음 단계(⬜ 미정): 토픽을 코드로 선언(common-messaging `NewTopic`), 운영 자동 생성 끄기. 규칙 → [이벤트 규칙](../conventions/events.md)

전환 중: market이 구매 확정을 `market.events.v1`로 옮기는 동안 settlement는 옛 토픽 `market.purchase-confirmed.v1`(봉투 없는 JSON, `shippingFee` 없음)도 함께 받습니다. 옛 토픽에 남은 메시지가 없으면 settlement의 옛 토픽 리스너를 지웁니다 (#364)

## 이벤트 목록

| 토픽 | 이벤트 | 구독 | 받아서 하는 일 |
|---|---|---|---|
| `member.events.v1` | `MemberCreated` · `MemberUpdated` · `MemberWithdrawn` | product · market · payment · settlement | `MEMBER_REPLICA`(닉네임 · 권한 · 판매자 여부) 저장 · 갱신 · 탈퇴 상태, `MemberCreated`면 장바구니(market) · 지갑(payment), `MemberWithdrawn`이면 상품 정리(product) |
| `member.address.events.v1` | `MemberAddressChanged` | market | **판매자 배송지 목록**(판매자가 주문 수락 때 택배 보내는 주소로 고름). 이름 · 연락처 · 주소는 전송 키로 암호화 |
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
| `product.events.v1` | 상품 변경 — `ProductRegistered` · `ProductUpdated` · `ProductStatusChanged` | product(색인 리스너) · ai | product: DB에서 최신 상품을 읽어 OpenSearch 문서를 덮어씀. ai: payload로 상품 스냅샷 upsert (`productVersion`이 저장값보다 클 때만) → [ADR 0021](../decisions/0021-product-event-payload-ai-snapshot.md) |
| `product.events.v1` | `ProductApproved` | notification | 판매자에게 통과 알림 (색인 리스너는 건너뜀, 상태는 함께 나가는 `ProductStatusChanged`로 색인) |
| `product.events.v1` | `ProductRejected` | notification | 판매자에게 반려 사유와 함께 알림 |
| `product.events.v1` | `ImageReviewDelayed` | notification | 관리자에게 「검수 지연 N건」 알림. 관리자 역할(ADMIN)로 알림 1건(`member_id` 없음, `recipient_role = ADMIN`)을 만들어 관리자끼리 읽음을 공유, `event_key`는 `ADMIN_REVIEW_DELAYED:{eventId}`(봉투 `eventId`), 새로 지연된 건만 1번, payload 추가 없음 (notification 담당 합의 11/19, 구독 #561). product 색인 리스너 · ai는 건너뜀 |
| `product.review.v1` | `ProductReviewRequested` (`review_version` 포함) | product | 사진 검수 (비동기) |
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
| `PurchaseConfirmed` | `sellerOrderId` · `sellerId` (숫자), `saleAmount` · `shippingFee` · `sellerBurdenAmount` (금액), `confirmedAt` (시각), `itemSummary` (문자열) | 정산은 `saleAmount`에만 수수료, `shippingFee`는 `REVENUE`로. `sellerBurdenAmount`(판매자 부담금)는 없으면 0, 정산금 = 판매액 − 수수료 − 판매자 부담금 |
| `ShipmentStatusChanged` | `shipmentId` · `orderId` · `sellerOrderId` · `buyerId` · `sellerId` (숫자), `status` (`BOOKED` · `PICKED_UP` · `IN_TRANSIT` · `DELIVERED`), `occurredAt` (시각), `itemSummary` (문자열) | `occurredAt`은 배송 단계가 일어난 시각 |
| `SellerOrderAccepted` | `orderId` · `sellerOrderId` · `sellerId` · `buyerId` (숫자), `rejectedItemCount` (숫자), `acceptedAt` (시각) | 판매자 수락 (#288). 판매자 주문당 1건, 수락 또는 거절 뒤 수락된 주문상품이 하나라도 남으면 발행 (남지 않으면 `SellerOrderRejected`). 전체 수락이면 `rejectedItemCount` 0, 일부 거절(수락된 상품이 남음)이면 이번에 거절한 주문상품 수 (구매자가 먼저 취소한 상품은 세지 않음) |
| `SellerOrderRejected` | `orderId` · `sellerOrderId` · `sellerId` · `buyerId` (숫자), `reason` (`SELLER` · `AUTO`), `rejectedAt` (시각) | 판매자 주문의 남은 상품 전부 거절 (#288). 판매자 거절이면 `SELLER`, 24시간 자동 거절이면 `AUTO`. 결정 시점에 발행하므로 환불이 나중에 `REVIEW_REQUIRED`가 되어도 이미 나간 상태. 판매자 거절, 자동 거절은 `SellerOrderCancelled`를 보내지 않음 |
| `Return*` · `OrderItemReturned` | ⬜ | 반품 때 |

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
| `ProductRegistered` | 아래 공통 칸 | 상품 등록 |
| `ProductUpdated` | 아래 공통 칸 | 제목 · 가격 · 사진 · 품종 등 수정 |
| `ProductStatusChanged` | 아래 공통 칸 | 예약 · 판매 완료 · 예약 해제 · 판매 재개 · 숨김 · 삭제 · 판매자 탈퇴 정리 · 검수 결과(통과 · 반려 · 판매 중 → 관리자 확인) · 이의 신청 · 기한 초과 재요청 |
| `ProductApproved` | `productId` · `sellerId` · `reviewVersion` (숫자), `productTitle` (문자열) | 검수 통과 알림용. 검수 중 → 판매 중으로 바뀔 때만 (판매 중 상품의 글 검사 통과는 보내지 않음) |
| `ProductRejected` | `ProductApproved` 칸 + `reasonCode` (반려 사유 코드, 예: `HARMFUL_IMAGE`) · `reasonMessage` (판매자 문구, `OTHER`면 관리자 입력) | 검수 반려 알림용 (글 검사 반려 포함). 관리자 확인으로 넘어갈 때는 보내지 않음 |
| `ImageReviewDelayed` | `delayedCount` (숫자, 이번에 묶은 건수), `checkedAt` (시각, 모은 시각) | 관리자 확인 12시간 지연 알림용 (#509). Key `image-review-delayed`(상품 하나에 묶이지 않음). 관리자 확인 지연 알림 스케줄러가 한국 시간 09~21시 매 정각에 확인해 새로 12시간을 넘긴 건(24시간 전)이 있을 때만 1개, 같은 건은 한 번만 ([사진 검수](../flows/image-review.md) 「관리자 수동 검수」). 시각은 product 서버 시간(서울)의 `LocalDateTime` |

세 이벤트의 공통 payload (`version` 3, [ADR 0021](../decisions/0021-product-event-payload-ai-snapshot.md) · [ADR 0031](../decisions/0031-chatbot-rag-product-document-payload-v3.md)):

| 칸 | 타입 | 내용 |
|---|---|---|
| `productId` | 숫자 | 상품 ID (Key와 같음) |
| `speciesId` | 숫자 | 품종 ID |
| `sellerId` | 숫자 | 판매자 회원 ID |
| `status` | 문자열 | 바뀐 뒤 상품 상태 (`REVIEW_PENDING` · `REJECTED` · `ACTIVE` · `PAYMENT_PENDING` · `SOLD_OUT` · `DELETED`) |
| `hidden` | 참/거짓 | 숨김 여부 |
| `productVersion` | 숫자 | 상품 버전. 상품 행을 바꾸는 모든 UPDATE에서 +1 (봉투 `version`과 다름). 받는 쪽은 저장된 값보다 클 때만 반영, 같으면 중복 |
| `title` · `description` · `growNote` · `potSize` | 문자열 | 제목 · 설명 · 키우기 메모 · 화분 크기 (v3, ai 챗봇 상품 문서). 없으면 `null` |
| `price` · `heightCm` | 숫자 | 가격 · 높이(cm) (v3, 챗봇 상품 카드 · 문서) |
| `thumbnailUrl` | 문자열 | 대표 사진(순서 0) URL (v3, 챗봇 상품 카드). 사진이 없으면 `null` |

v3부터 제목 · 가격 · 대표 사진 · 설명 · 키우기 메모 · 크기를 싣습니다(ai 챗봇의 상품 문서 · 카드, ADR 0031). 장바구니 · 추천 카드는 지금처럼 market이 상품 ID로 조회합니다. ai는 version 2 · 3을 모두 받고, v2로 저장된 상품은 다음 이벤트 때 문서가 생깁니다. payload 값은 발행하는 트랜잭션에서 상품을 다시 읽어 채우고(`ProductEventPublisher` 한 곳에서, 엔티티가 아니라 DB 값을 바로 읽음), 상태 · 숨김을 바꾸는 곳은 반드시 새 값이 담긴 이벤트를 보냅니다. 순서는 `productVersion`으로 판단하고 봉투 `occurredAt`은 로그용입니다. ai가 이 payload로 상품 스냅샷을 저장하고(#293), 칸 추가는 공통 매퍼의 `FAIL_ON_UNKNOWN_PROPERTIES` 비활성으로 호환됩니다.

`ProductApproved` · `ProductRejected`는 notification이 product를 조회하지 않고 payload만으로 알림을 만들도록 판매자 · 상품 이름 · 사유를 싣습니다. 상품 상태 동기화(ai 스냅샷)는 같은 트랜잭션에서 함께 보내는 `ProductStatusChanged`가 맡습니다.

검수 요청 `ProductReviewRequested`는 이 토픽이 아니라 `product.review.v1`(Key `productId`)로 보냅니다. payload는 `productId` · `reviewVersion`(숫자)이고, 등록 · 수정 트랜잭션에서 `IMAGE_REVIEW`(REQUESTED)와 함께 저장합니다.

product 색인 리스너는 상품 ID로 DB에서 최신 상품을 다시 읽습니다 (같은 이벤트가 두 번 와도 결과가 같음). ai는 payload로 저장하고 product를 조회하지 않습니다. 주문 번호 · 판매자 기준으로 여러 상품을 한 번에 바꿀 때는 잠근 상품마다 보내므로, 실제로 바뀌지 않은 상품이 섞일 수 있습니다.

회원 이벤트(`member.events.v1`) payload → [member](../services/member.md)

⬜ 나머지 이벤트 payload는 각 발행 서비스 담당이 채웁니다 (#227).


### member 판매자 배송지 (`member.address.events.v1`, Key `memberId`, #233)

판매자 등록 · 배송지 추가 · 기본 배송지 변경 · 수정(실제로 바뀐 경우만) · 삭제 때, 판매자의 **바뀐 뒤 배송지 목록 전체**를 보냅니다 (판매자가 아니면 보내지 않음).

| 봉투 payload | 타입 | 내용 |
|---|---|---|
| `memberId` | 숫자 | 판매자 회원 id (Key와 같음) |
| `encryptedAddresses` | 문자열 | 아래 주소 JSON을 [민감 필드 암호화](../conventions/events.md#민감-필드-암호화)로 암호화한 값 (`v1.k1:...`) |

암호화 전 주소 JSON (`{ memberId, addresses: [...] }`):

| 칸 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `memberId` | 숫자 | ✅ | 봉투 payload의 `memberId`와 같아야 함 |
| `addresses` | 목록 | ✅ | 바뀐 뒤 전체 (1 ~ 4개), 기본 배송지 먼저 · 최신 순 |
| `addresses[].addressId` | 숫자 | ✅ | member의 배송지 id. 판매자가 수락 때 고른 주소를 가리킴 |
| `addresses[].isDefault` | 참/거짓 | ✅ | 목록에서 정확히 1개만 `true` |
| `addresses[].recipientName` | 문자열 | ✅ | |
| `addresses[].recipientPhone` | 문자열 | ✅ | 정상 등록 경로에서는 휴대폰 11자리(하이픈 없음)로 검증하며, 이벤트 발행 시에는 재검증하지 않는다 |
| `addresses[].zipcode` | 문자열 | ✅ | 5자리, 앞자리 0을 지키려고 문자열 |
| `addresses[].address1` | 문자열 | ✅ | 도로명 주소 |
| `addresses[].address2` | 문자열 | ❌ | 없으면 칸을 두고 `null` |

배송지 이름(`addressName`)은 넣지 않습니다 (택배 예약에 필요 없고, 복제하는 개인정보를 줄임).

받는 쪽 규칙:
- 복호화한 `memberId`가 봉투 payload의 `memberId`와 다르면 저장하지 않고 거절한다 (다른 회원 암호문 교체 방지, **필수**)
- 받은 목록으로 그 판매자의 주소를 통째로 바꾼다 (목록에 없는 주소는 지움)
- 기존 이벤트 재전송은 원래 `occurredAt`을 유지하며, 저장된 시각보다 새로운 이벤트만 반영한다
- 평문 주소 · JSON은 로그에 남기지 않는다

예시 (가짜 값, 암호화 전):

```json
{
  "memberId": 7,
  "addresses": [
    { "addressId": 15, "isDefault": true, "recipientName": "홍길동", "recipientPhone": "01012345678",
      "zipcode": "06236", "address1": "서울 강남구 테헤란로 1", "address2": "5층" },
    { "addressId": 12, "isDefault": false, "recipientName": "홍길동", "recipientPhone": "01012345678",
      "zipcode": "04524", "address1": "서울 중구 세종대로 110", "address2": null }
  ]
}
```
