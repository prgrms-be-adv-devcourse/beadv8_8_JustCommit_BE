# ai-service

> 언제 읽나: ai-service 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-12 · 관련: Miro 5 · 17, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 품종 임베딩 | 자체 시드한 품종 프로필로 품종 문서(품종명 + 별칭 + 학명 + 생육 정보)를 만들어 외부 API로 임베딩 → pgvector ([ADR 0020](../decisions/0020-species-profile-ai-seed.md)) |
| 추천 | 장바구니에 담은 품종과 생육 조건이 비슷한 품종의 판매 상품 ("같이 키우기 좋은 식물") |
| 챗봇 | 식물 상담 · 문장으로 식물 찾기 (⬜ 범위 보류) |

흐름 → [검색 · 추천](../flows/search-recommend.md). K3s, HPA CPU 70% 1→2개 → [배포](../infra/deploy.md)

## 소유 테이블

pgvector (ai DB). 품종 벡터는 speciesId 기준. 테이블 이름은 ⬜ 미정 (Spring AI `PgVectorStore` 기본값 검토)

## 외부 API

챗봇 경로 (⬜ 미정, Gateway 응답 20초) → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

| API | 부르는 서비스 | 상태 |
|---|---|---|
| `/internal/v1/recommendations` | market (장바구니 추천) | 🟨 제안 |

상품 의미 검색은 범위 밖이라 product 호출이 없습니다. 상세 → [내부 API 목록](../reference/internal-apis.md)

## 호출하는 내부 API

| 대상 | 용도 | 상태 |
|---|---|---|
| product | 추천 · 검색 결과의 상품 정보 · 판매 상태 일괄 조회 (`GET /internal/v1/products?ids=`) | ✅ |

## 발행 이벤트

없음

## 구독 이벤트

| 이벤트 | 받아서 하는 일 | 멱등 처리 |
|---|---|---|
| `product.events.v1` `ProductApproved` | 품종별 추천 후보에 추가 (⬜ 이벤트 필드 확인 중 — 성수 님) | productId upsert |

## 스케줄러

두지 않음 (2개까지 늘어나 중복 실행 위험)

## 정책

| 정책 | 내용 |
|---|---|
| 이미지 기반 추천 | 하지 않음 |
| 장애 시 | 추천 영역만 비고 장바구니 · 주문은 영향 없음. market이 기본 추천으로 대체 |

## 테스트 시나리오

⬜ 추가 예정 → [테스트 시나리오](../testing/scenarios.md)
