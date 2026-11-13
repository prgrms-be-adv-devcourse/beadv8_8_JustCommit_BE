# 0021. 상품 이벤트에 품종 · 판매자 · 상태 · 상품 버전을 싣고, ai는 product를 조회하지 않음

> 상태: ✅ 확정 · 결정일 2026-11-13 · 결정한 사람: product · ai 담당 (#293), 팀 피드백 반영 (김환희 · 장세미)
> 대체: - · 대체됨: -

## 배경

장바구니 추천(#289)은 비슷한 품종의 **판매 중 상품**을 골라야 하고, 최근 구매 상품 기반 추천(#294)은 구매한 상품(`SOLD_OUT`)을 품종으로 바꿔야 합니다. ai는 품종 벡터만 갖고 있어서 상품 ID → 품종 · 상태를 알 방법이 필요했습니다.

#338에서 `product.events.v1` payload는 `productId` 하나로 정해졌습니다. 이대로면 ai는 이벤트를 받을 때마다 product를 조회해야 합니다. 정해야 할 것은 세 가지였습니다.

| 정할 것 | 질문 |
|---|---|
| 상품 정보 전달 | ai가 상품의 품종 · 상태를 어떻게 알게 할까 |
| 순서 기준 | 이벤트가 중복되거나 순서가 바뀌면 무엇으로 최신을 판단할까 |
| 적재 | ai DB가 비어 있을 때(처음 · 복구) 어떻게 채울까 |

## 결정

**상품 정보 전달: A안.** `ProductRegistered` · `ProductUpdated` · `ProductStatusChanged` payload에 값을 싣습니다 (봉투 `version` 2). 가격 · 제목은 넣지 않습니다. ai는 세 이벤트를 모두 구독해 `product_snapshot`에 **모든 상태**를 `productId`로 upsert하고, 추천할 때 `ACTIVE`이고 숨김이 아닌 것으로 거릅니다. ai → product 동기 호출은 없습니다. 카드에 보일 제목 · 가격 · 사진은 market이 추천 결과 ID로 product를 조회합니다.

| 칸 | 내용 |
|---|---|
| `productId` · `speciesId` · `sellerId` | 상품 · 품종 · 판매자 ID |
| `status` · `hidden` | 바뀐 뒤 상태 · 숨김 |
| `productVersion` | 상품 버전. 상품 행(`version bigint`)을 바꾸는 모든 UPDATE에서 +1 (봉투 `version`과 다름) |

**순서 기준: 상품 버전.** ai는 `productVersion`이 저장된 값보다 클 때만 반영하고, 같거나 작으면 버립니다. 봉투 `occurredAt`은 로그용으로만 씁니다.

**적재: 전체 상품 이벤트 재발행 운영 API (🟨 제안).** 모든 상태를 보내고, 받는 쪽은 `productVersion` 비교로 최신 값을 덮지 않습니다.

| 경우 | 필요 |
|---|---|
| 초기 적재 | 운영 전에 상품 데이터를 비우면 필요 없음, 세미 프로젝트 상품을 옮기면 필요 (⬜ 팀 확인) |
| 복제본 재구축 | 항상 필요. Kafka 보관 7일이 지난 뒤 복구, 구매 상품을 품종으로 바꾸려면 모든 상태가 있어야 함 |

**최근 구매 상품:** market이 `orders_item.status = CONFIRMED`인 상품 ID를 추천 요청에 보냅니다. 취소 · 반품된 주문상품은 확정될 수 없어 따로 뺄 필요가 없습니다. 개수 · 기간은 ⬜ 미정입니다.

### product가 할 일

| 할 일 | 내용 |
|---|---|
| 상품 버전 | `PRODUCT`에 `version bigint` 추가 (마이그레이션 1개). 엔티티 수정과 일괄 UPDATE 쿼리 5개(`reserve` · `release` · `markSoldOut` · `releaseItem` · `deleteOnSaleBySeller`) 모두 `version = version + 1` |
| 발행 공통 메서드 | 발행하는 7곳(`ProductService` 6곳 · `MemberReplicaService` 1곳)이 「상품 ID 목록 → 상품 조회 → payload」 공통 메서드를 씀 |
| 일괄 UPDATE 뒤 새 값 | 일괄 UPDATE 5개가 모두 `@Modifying(flushAutomatically = true, clearAutomatically = true)`라 같은 트랜잭션에서 다시 읽으면 새 값이 나옴 |
| 상태를 바꾸는 곳 | 검수 결과(#345) 포함, 반드시 새 상태가 담긴 이벤트를 발행 |
| 재발행 운영 API | 위 「적재」 |
| 테스트 | (a) 판매 완료 · 예약 해제 · 판매자 탈퇴 정리 뒤 Outbox payload에 새 `status` · `productVersion`이 담김 (b) 롤백하면 상품 변경과 Outbox 행이 함께 사라짐 |

## 고른 이유

### 상품 정보 전달

| 안 | 방식 | 장점 | 단점 |
|---|---|---|---|
| **A (선택)** | payload에 `speciesId` · `sellerId` · `status` · `hidden` · `productVersion`, ai는 받은 값으로 저장 | ai → product 호출 없음. 받는 쪽이 늘어도 product를 고칠 일이 없음. 모든 상태가 남아 최근 구매 상품도 품종으로 바꿀 수 있음 | product 변경이 큼 (payload · 버전 칸 · 발행 7곳 · UPDATE 5개). payload가 커짐 |
| B | payload는 `productId`만, ai가 이벤트 3종마다 상품 내부 API로 조회 | product 변경이 거의 없음. 항상 조회 시점 값을 씀 | 실행 중 ai → product 결합, product 장애 때 ai 처리가 멈춤. 받는 쪽마다 조회가 늘어 부하 · 결합이 받는 쪽 수만큼 커짐. 조회 · 저장이 같은 상품끼리 순차 처리될 때만 안전(같은 key · 같은 파티션 · 블로킹 재시도 [ADR 0017](0017-blocking-retry-dlt.md)) |
| 축소 B (11/12 결정, PR #425 원안) | `ProductStatusChanged`만 받아 조회, 판매 중만 저장 | 이벤트 수 · 조회 수가 가장 적음. 저장할 행이 적음 | B의 결합 문제 그대로. 판매 중만 남아 최근 구매 상품을 품종으로 바꿀 수 없음. 「상태 변경만 중요」라는 전제는 글 기반 임베딩이 들어오면 깨짐. 품종만 바꾸는 수정은 `FULL` → `REVIEW_PENDING`(`Product.java` 146 · 158)인데 `updateProduct`는 `ProductUpdated`만 발행해(`ProductService.java` 261) 이 경로를 놓침 |
| C | 이벤트 없이 추천 요청마다 product에서 품종별 판매 중 상품 조회 | ai에 상품 데이터가 없어 동기화 문제가 없음 | 품종별 판매 중 상품 API가 없음. product 장애 · 지연이 추천 응답에 바로 번짐 |

A를 고른 이유:

- 이번 프로젝트의 목표가 서비스 분리입니다. A에서 ai가 기대는 것은 Kafka(들어옴)와 market의 추천 요청(들어옴)뿐입니다.
- 상품 데이터를 받는 곳이 늘 수 있습니다(색인 · ai 추천 · 나중에 챗봇 · 검색 임베딩). B 계열은 받는 쪽마다 product를 조회하지만, A는 받는 쪽이 늘어도 product가 그대로입니다.
- #294는 최근 구매 상품(`SOLD_OUT`)을 품종으로 바꿔야 해서 모든 상태가 남아 있어야 합니다.
- product 변경이 크다는 단점은 발행 공통 메서드 하나로 모으고 테스트 2개로 막습니다.

### 순서 기준

| 안 | 장점 | 단점 |
|---|---|---|
| **상품 버전 `productVersion` (선택)** | 같은 상품의 변경은 행 잠금으로 줄을 서므로 엄격히 증가. 재시도 · DLT 재처리 · 재발행으로 순서가 바뀌거나 중복돼도 비교 한 번으로 걸러짐 | `PRODUCT`에 칸 추가 · 모든 UPDATE에 +1 필요 |
| 봉투 `occurredAt` | 칸 추가 없음 | `OutboxPublisher.publish`에서 서버 시계로 찍음(`EventEnvelopeMapper.wrap`). 파드 간 시계 차이, 정밀도(Outbox `created_at` `timestamp(6)`), 같은 트랜잭션에서 같은 시각이 찍히는 문제로 엄격한 순서를 보장하지 못함 |
| 비교 없이 마지막 도착 값 | 구현이 가장 단순 | 재시도 · 재발행 때 오래된 값이 최신 값을 덮음 |

### 적재

| 안 | 장점 | 단점 |
|---|---|---|
| **전체 상품 이벤트 재발행 운영 API (선택, 🟨)** | 평소와 같은 이벤트 경로라 ai 코드가 하나. ai → product REST 호출이 없다는 원칙 유지. 버전 비교로 최신 값을 덮지 않음. 상품 조회 내부 API에 `speciesId`를 더할 필요 없음 | product에 운영 API 추가, 한 번에 이벤트가 많이 나감 |
| ai가 상품 내부 API로 한 번 적재 | 이벤트를 늘리지 않음 | ai → product 호출이 생기고 내부 API에 `speciesId` · 목록 조회가 필요. 적재 경로가 이벤트 경로와 따로 놀아 코드가 둘 |
| 적재하지 않음 | 할 일 없음 | Kafka 보관 7일이 지나면 복구할 방법이 없음 |

## 다른 안

위 「고른 이유」의 표에 장단점을 적었습니다. 버린 안만 한 줄씩 남깁니다.

| 안 | 버린 이유 |
|---|---|
| B (ID만 보내고 이벤트마다 조회) | 실행 중 ai → product 결합, 받는 쪽마다 조회가 늘어남 |
| 축소 B (`ProductStatusChanged`만 받아 조회, 판매 중만 저장) | 결합에 더해 최근 구매 상품을 품종으로 바꿀 수 없고, 품종만 바꾸는 수정 경로를 놓침 |
| C (추천 요청마다 product 조회) | API가 없고 product 장애가 추천에 번짐 |
| 순서 기준 `occurredAt` | 서버 시계 · 정밀도 문제로 엄격한 순서를 보장하지 못함 |
| ai가 내부 API로 적재 | ai → product 호출이 생기고 적재 코드가 따로 생김 |

## 영향

- [Kafka 토픽 표](../reference/kafka-topics.md): `product.events.v1` payload 칸 · `version` 2, 구독자 ai
- [ai](../services/ai.md) 소유 테이블 · 구독 이벤트, 호출하는 내부 API에서 product 조회 삭제
- [product](../services/product.md) 소유 테이블(`version`) · 발행 이벤트 · 재발행 운영 API, [내부 API 목록](../reference/internal-apis.md) 상품 조회는 market만 · 추천 요청 형식
- [검색 · 추천 흐름](../flows/search-recommend.md) 「추천」, [사진 검수](../flows/image-review.md) 결과별 처리
- 현재 `product.events.v1` 구독자는 없음 (develop 기준, product 색인 리스너 미구현). 칸 추가는 공통 매퍼(`EventEnvelopeMapper`)의 `FAIL_ON_UNKNOWN_PROPERTIES` 비활성으로 호환
- 범위 밖: 검색 임베딩(상품 의미 검색)은 ⬜ → [미정 항목](../open-questions.md)
