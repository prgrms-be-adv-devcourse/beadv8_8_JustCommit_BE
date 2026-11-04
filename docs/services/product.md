# product 서비스

> 언제 읽나: product 서비스 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro 5 · 13-1 · 17, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 상품 | 등록 · 수정 · 삭제 · 숨김, 판매 상태(판매중 · 결제중 · 판매완료) |
| 예약 · 판매완료 | market의 내부 REST 호출로 예약 · 해제 · 판매완료 · 재판매 처리 |
| 품종 | 농사로 217종 품종 정보, 학명 · 별칭 |
| 검색 | OpenSearch 검색 · 필터 · 정렬, `product.events.v1`을 받는 색인 리스너 → [검색 · 추천 흐름](../flows/search-recommend.md) |
| 사진 검수 | Rekognition(유해 사진) + Plant.id(품종) + 금칙어 → [사진 검수 흐름](../flows/image-review.md) |

## 소유 테이블

| 테이블 | 설명 |
|---|---|
| `PRODUCT` | 상품 |
| `PRODUCT_IMAGE` | 상품 사진 |
| `SPECIES` | 품종 (학명 `scientific_name` 포함) |
| `SPECIES_IMAGE` | 품종 기준 사진 |
| `SPECIES_ALIAS` | 품종 별칭 |
| `IMAGE_REVIEW` | 사진 검수 기록 |
| `BANNED_KEYWORD` | 금칙어 |
| `MEMBER_REPLICA` | 회원 복제본 (닉네임 · 판매자 여부) |
| `OUTBOX` | 발행 대기 이벤트 |

## 외부 API

`products` (목록 · 등록 · 상세 · 수정 · 삭제) · `products/{id}/hidden` · `products/me` · `products/me/counts` · `products/images/upload-urls` · `species` · `species/{id}` · `admin/imageReviews` → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

| API | 부르는 서비스 | 멱등 키 |
|---|---|---|
| `GET /internal/v1/products?ids=` | market · ai | – |
| `POST /internal/v1/products/reservations` | market | orderId |
| `DELETE /internal/v1/products/reservations/{orderId}` | market | orderId |
| `POST /internal/v1/products/reservations/{orderId}/sold-out` | market | orderId |
| `POST /internal/v1/products/{productId}/release` | market | productId |

상세 → [내부 API 목록](../reference/internal-apis.md)

## 호출하는 내부 API

| 대상 | 용도 | 상태 |
|---|---|---|
| ai-service | 검색할 때 의미 검색 결과(상품 ID · 점수) 조회, 실패하면 키워드 결과만 | 🟨 제안 |

## 발행 이벤트

| 토픽 | 이벤트 |
|---|---|
| `product.events.v1` | `ProductRegistered` · `ProductUpdated` · `ProductStatusChanged` · `ProductApproved` · `ProductRejected` |
| `product.review.v1` | `ProductReviewRequested` |
| `species.events.v1` | `SpeciesProfileUpdated` |

상세 → [Kafka 토픽 표](../reference/kafka-topics.md)

## 구독 이벤트

| 이벤트 | 받아서 하는 일 | 멱등 처리 |
|---|---|---|
| 회원 토픽 (`member.events.v1`) | `MEMBER_REPLICA` 저장 · 갱신 · 탈퇴 처리 (없으면 JWT memberId로 get-or-create) | memberId upsert |
| `product.events.v1` (자기 토픽) | OpenSearch 색인 | productId upsert |
| `product.review.v1` (자기 토픽) | 사진 검수 실행 | 검수 상태 확인 |

주문 · 상품 판매 이벤트는 구독하지 않음 (판매완료는 market → product 내부 REST)

## 스케줄러

없음

## 정책

세미 프로젝트 정책 문서(`docs/policy.md`, 삭제됨)에서 옮긴 확정 정책입니다.

### 상품 등록
1. 판매자(SELLER)만 상품을 등록할 수 있다.
2. 제목, 설명, 가격, 품종은 반드시 입력한다.
3. 높이, 화분 크기, 성장 메모는 선택 항목이다.
4. 사진은 최소 1장 이상 반드시 등록해야 하며, 최대 10장까지 등록할 수 있다. 첫 번째 사진을 대표 사진으로 사용한다.
5. 판매자 한 명이 등록할 수 있는 상품 수는 제한하지 않는다.
6. 판매 금지 품종으로는 상품을 등록할 수 없다.
7. 농사로 품종 목록에 없는 식물은 "기타" 품종으로 등록한다.

### 상품 노출
1. 판매 중인 상품은 목록과 상세에 노출한다.
2. 결제 대기 중인 상품은 노출하며 "거래 중"으로 표시한다.
3. 판매 완료된 상품은 노출하며 "판매 완료"로 표시한다.
4. 삭제된 상품은 노출하지 않는다.
5. 숨긴 상품은 판매자 본인 외에는 노출하지 않는다. 판매자 본인은 숨긴 상품의 상세를 조회할 수 있다. 다른 사람이 숨긴 상품의 상세를 조회하면 삭제된 상품(`PRODUCT_NOT_FOUND`)과 구분해 `PRODUCT_HIDDEN`으로 응답한다.
6. 인기순 정렬은 기준 지표(조회수, 찜 수 등)가 필요하므로 최종 프로젝트에서 적용한다. 그 전까지 인기순은 최신순과 같게 동작한다.

### 상품 수정, 삭제, 숨김
1. 상품은 판매 중일 때만 수정할 수 있으며, 품종을 포함한 모든 항목을 수정할 수 있다.
2. 상품은 판매 중일 때만 삭제할 수 있으며, 삭제된 상품은 데이터를 지우지 않고 삭제 상태로 보관한다.
3. 상품은 삭제된 상태가 아니면(판매 중, 거래 중, 판매 완료) 숨길 수 있다. 숨긴 상품은 새로 주문할 수 없지만, 이미 진행 중인 결제는 그대로 진행한다.

### 주문 이후
1. 판매자는 자신이 등록한 상품을 구매할 수 없다.
2. 상품은 주문 시점에 거래 중(결제 대기)으로 예약하고, 결제가 완료되면 판매 완료로 처리한다. 결제에 실패해도 결제 대기 시간 동안은 거래 중을 유지하며, 결제 대기 시간이 지나면 다시 판매 중으로 복원한다.
3. 주문한 상품 중 일부만 취소할 수 있으며, 취소된 상품만 다시 판매 중으로 복원한다.
4. 환불된 상품은 다시 판매하지 않고 판매 종료로 처리한다.

## 테스트 시나리오

S-C01 ~ S-C05 → [테스트 시나리오](../testing/scenarios.md)
