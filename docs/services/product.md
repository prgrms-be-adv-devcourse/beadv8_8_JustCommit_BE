# product 서비스

> 언제 읽나: product 서비스 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-13 · 관련: Miro 5 · 13-1 · 17, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 상품 | 등록 · 수정 · 삭제 · 숨김, 상태 6개, 판매자별 등록 제한 |
| 예약 · 판매 완료 · 판매 재개 | 예약은 market의 내부 REST, 판매 완료 · 결제 전 예약 해제 · 판매 재개는 market 이벤트 구독 (거절 · 반품이면 숨김) → [ADR 0016](../decisions/0016-product-sale-events-from-market.md) |
| 품종 | 농사로 215종, 학명 · 학명 동의어 · 별칭. 판매 금지 품종은 `banned_species`로 따로 |
| 검색 | OpenSearch, 색인 리스너 → [검색 · 추천](../flows/search-recommend.md) |
| 사진 검수 | OpenAI Moderation + Claude Haiku, 관리자 확인, 이의 신청 → [사진 검수](../flows/image-review.md) |

## 소유 테이블

| 테이블 | 설명 |
|---|---|
| `PRODUCT` | 상품 (`status` · `hidden` · `review_version` · 예약한 `orderId` · 상품 버전 `version` 🟨) |
| `PRODUCT_IMAGE` | 상품 사진 (1~10장) |
| `SPECIES` | 품종 (학명 `scientific_name`, 금지 품종 없음) |
| `SPECIES_IMAGE` | 품종 기준 사진 |
| `SPECIES_ALIAS` | 별칭 · 학명 동의어 |
| `BANNED_SPECIES` | 판매 금지 품종 (법령 학명, 속 · 종 · 변종 단위) |
| `BANNED_SPECIES_ALIAS` | 금지 품종 최신 학명 · 한글 별칭 |
| `BANNED_KEYWORD` | 품종이 아닌 금지어 · 관리자 확인 단어 · 예외 단어 |
| `PRODUCT_RESERVATION` | orderId별 예약 기록 (orderId 기본키, 해제되면 `RELEASED`) |
| `IMAGE_REVIEW` | 검수 버전별 단계 결과 · 판정 · 관리자 판단 · 이의 신청 메모 |
| `MEMBER_REPLICA` | 회원 복제본 |
| `OUTBOX_EVENT` | 발행 대기 이벤트 (`common-messaging-outbox`, Flyway V9) |

## 외부 API

`products` (목록 · 등록 · 상세 · 수정 · 삭제) · `products/{id}/hidden` · `products/me` · `products/me/counts` · `products/images/uploadUrls` · `species` · `species/{id}` · `admin/imageReviews` · ⬜ 이의 신청 → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

| API | 부르는 서비스 | 멱등 키 |
|---|---|---|
| `GET /internal/v1/products?ids=` | market | – |
| `POST /internal/v1/products/reservations` (`expectedPrice`, 409 `PRICE_CHANGED`) | market | orderId |

상세 → [내부 API 목록](../reference/internal-apis.md)

## 호출하는 내부 API

없음. 외부: OpenAI Moderation · Claude Haiku (사진 검수), S3 (EC2 역할)

## 발행 이벤트

| 토픽 | 이벤트 |
|---|---|
| `product.events.v1` | `ProductRegistered` · `ProductUpdated` · `ProductStatusChanged` · `ProductApproved` · `ProductRejected` |
| `product.review.v1` | `ProductReviewRequested` |

품종 이벤트(`species.events.v1`)는 발행하지 않습니다 → [ADR 0020](../decisions/0020-species-profile-ai-seed.md)

`ProductRegistered` · `ProductUpdated` · `ProductStatusChanged`에는 `productId` · `speciesId` · `sellerId` · `status` · `hidden` · `productVersion`을 싣고(봉투 `version` 2), 상품 행을 바꾸는 모든 UPDATE에서 `version`을 +1, 상태 · 숨김을 바꾸는 곳은 새 값이 담긴 이벤트를 보냅니다 (ai 상품 스냅샷). 초기 적재 · 복제본 재구축용 「전체 상품 이벤트 재발행」 운영 API는 🟨 제안 → [ADR 0021](../decisions/0021-product-event-payload-ai-snapshot.md)

상세 → [Kafka 토픽 표](../reference/kafka-topics.md)

## 구독 이벤트

| 이벤트 | 받아서 하는 일 | 멱등 처리 |
|---|---|---|
| `member.events.v1` | `MEMBER_REPLICA` 저장 · 갱신, `MemberWithdrawn`이면 판매 중 · 검수 중 · 반려 상품 `DELETED` | memberId upsert |
| `product.events.v1` (자기 토픽) | DB에서 최신 상품을 읽어 OpenSearch 색인 | 문서 ID = 상품 ID |
| `product.review.v1` (자기 토픽) | 사진 검수 실행 | `review_version` · 상태 확인 |
| `market.order-paid.v1` | 판매 완료: 이 주문이 잡은 `PAYMENT_PENDING` 상품만 `SOLD_OUT` | orderId 조건, 0건이면 정상 |
| `market.order-expired.v1` · `market.order-failed.v1` | 결제 전 예약 해제: `PAYMENT_PENDING` → `ACTIVE`, `PRODUCT_RESERVATION` `RELEASED` | orderId 조건, 행 잠금 |
| `market.order-item-cancelled.v1` | 판매 재개: `BUYER`면 `ACTIVE`, `SELLER` · `AUTO`면 `ACTIVE` + 숨김 | orderId 조건, 0건이면 정상 |
| `market.order-item-returned.v1` | 판매 재개: `ACTIVE` + 숨김 | orderId 조건, 0건이면 정상 |

## 스케줄러

관리자 확인 지연 · Outbox 재전송 → [스케줄러 표](../reference/schedulers.md)

## 정책

노션 「정책 정리」 3절 기준입니다.

| 상태 | 뜻 | 구매 | 검색 노출 |
|---|---|---|---|
| `REVIEW_PENDING` | 등록 후 검수 중 | ❌ | ❌ |
| `REJECTED` | 검수 반려 | ❌ | ❌ |
| `ACTIVE` | 판매 중 | ✅ | ✅ |
| `PAYMENT_PENDING` | 주문에 예약됨 | ❌ | ✅ "거래 중" |
| `SOLD_OUT` | 판매 완료 | ❌ | ✅ "판매 완료" |
| `DELETED` | 소프트 삭제 | ❌ | ❌ |

| 주제 | 규칙 |
|---|---|
| 숨김 | 상태와 별개. 판매자 본인만 봄, 남이 상세를 열면 403 `PRODUCT_HIDDEN`(삭제 404와 구분), 새 주문 불가, 장바구니에는 "숨긴 상품" |
| 검수 중 · 반려 노출 | `REVIEW_PENDING` · `REJECTED`는 판매자 본인만 봄(내 상품 목록 · 상세), 남이 상세를 열면 404 `PRODUCT_NOT_FOUND`(검색에도 안 나와 없는 상품처럼), 예약 불가(409 `PRODUCT_NOT_RESERVABLE`). 내부 API(`ProductResult`)는 제목을 「검수 중인 상품」으로 가리고 썸네일을 비움(장바구니에 반려 사진 · 글이 보이지 않게) |
| 등록 | SELLER만 (403 `NOT_SELLER`). 품종 · 제목 · 설명 · 가격 · 사진 필수. 사진은 본인이 Presigned URL로 올린 JPEG · PNG, 10MB 이하, 1~10장 |
| 수정 | 본인 · `ACTIVE` · `REJECTED` · `REVIEW_PENDING`만, 전체 교체, 검수 버전 +1. 판매 중 상품의 글만 바뀌면 판매를 이어 가며 글 검사만 (걸리면 `REJECTED`, 관리자 확인이면 `REVIEW_PENDING`), 그 밖에는 처음부터 검수(사진 · 품종이 바뀜, 검수 중 · 반려 상품). 예약과 겹치지 않게 행 잠금 |
| 삭제 | 본인 · `ACTIVE` · `REJECTED` · `REVIEW_PENDING`만, 소프트 삭제, 사진은 커밋 후 S3에서 삭제 |
| 재검수 · 이의 신청 | 재검수 1회, 자동 반려는 수정 없이 이의 신청 1회 → [사진 검수](../flows/image-review.md) |
| 등록 제한 | 검수 중 동시 5개(409 `REVIEW_PENDING_LIMIT_EXCEEDED`, 이의 신청 포함) · 하루 등록 20건(429 `DAILY_REGISTER_LIMIT_EXCEEDED`, 삭제 포함) · 하루 사진 검수 30회(429 `DAILY_REVIEW_LIMIT_EXCEEDED`, 등록 · 사진 검수가 필요한 수정마다 `IMAGE_REVIEW` 행으로 셈, 글 검사는 제외). 하루는 한국 시간 자정 기준. 최근 7일 반려 5건 이상이면 사진 검수를 외부 API 없이 바로 관리자 확인 (`REVIEW_EXPIRED` 제외) |
| 예약 | market 내부 API로만, 주문서 가격(`expectedPrice`)이 하나라도 다르면 409 `PRICE_CHANGED`, 해제된 orderId의 늦은 예약은 409 → [내부 API 목록](../reference/internal-apis.md) |
| 판매 완료 · 예약 해제 | market 이벤트로 처리. 판매 완료는 이 주문이 잡은 `PAYMENT_PENDING` 상품만 `SOLD_OUT`, 결제 전 해제(만료 · 결제 확정 실패)는 `PAYMENT_PENDING` → `ACTIVE`. 바뀐 상품이 0건이어도 정상(로그만, DLT 아님). 예약 · 판매 완료 · 해제는 모두 orderId 기준이라 다른 주문이 잡은 상품은 건드리지 않음 |
| 판매 재개 | 환불 성공 뒤 market 이벤트로. 이 주문이 잡은 상품(`reservedOrderId = orderId`)이 `PAYMENT_PENDING` 또는 `SOLD_OUT`일 때만 `ACTIVE`로 되돌리고 `reservedOrderId`를 비움 (취소가 판매 완료보다 먼저 와도 맞게). 구매자 취소(`BUYER`)는 숨기지 않음, 판매자 거절 · 자동 거절(`SELLER` · `AUTO`) · 반품이면 `ACTIVE` + **숨김**. 해제 메서드는 `releaseItem(productId, orderId, hidden)` 하나. 이미 다른 사람에게 다시 팔린 상품은 그대로. 숨김을 풀 때 재검수 없음 |
| 판매자 탈퇴 | 판매 중(숨김 포함) · 검수 중 · 반려 상품 `DELETED`, 판매 완료는 남김. 탈퇴 이후 해제되는 상품도 `DELETED` |
| 판매 금지 | 국내법(야생생물법 멸종위기 Ⅰ · Ⅱ급, 마약류관리법, 생물다양성법 생태계교란 생물) 대상, 인공증식도 금지. 파피오페딜럼은 금지 아님("기타"로 등록). 씨앗 · 삽수 · 구근 등 심지 않은 형태는 판매 불가. 목록은 학기마다 다시 확인 |

## 테스트 시나리오

S-A06 · S-C01 ~ S-C09 → [테스트 시나리오](../testing/scenarios.md)
