# ai-service

> 언제 읽나: ai-service 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-13 · 관련: Miro 5 · 17, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 품종 임베딩 | 자체 시드한 품종 프로필로 품종 문서(품종명 + 별칭 + 학명 + 생육 정보)를 만들어 외부 API로 임베딩 → pgvector ([ADR 0020](../decisions/0020-species-profile-ai-seed.md)) |
| 추천 | 장바구니에 담은 품종과 생육 조건이 비슷한 품종의 판매 상품 ("같이 키우기 좋은 식물"). 상품은 상품 스냅샷에서 판매 중만 고름 ([ADR 0021](../decisions/0021-product-event-payload-ai-snapshot.md)) |
| 챗봇 | 식물 상담 · 문장으로 식물 찾기 (⬜ 범위 보류) |

흐름 → [검색 · 추천](../flows/search-recommend.md). K3s, HPA CPU 70% 1→2개 → [배포](../infra/deploy.md)

## 소유 테이블

DB `ai_db` · 계정 `ai_app` (pgvector 확장 `vector`, Flyway V1) → [DB · Flyway](../infra/database.md)

| 테이블 | 설명 |
|---|---|
| `species_profile` | 농사로 자료로 시드한 품종 프로필 (품종 ID = product `species.id`) → [ADR 0020](../decisions/0020-species-profile-ai-seed.md) |
| 품종 벡터 (pgvector) | 품종 문서 임베딩, speciesId 기준. 테이블 이름은 ⬜ 미정 (Spring AI `PgVectorStore` 기본값 검토) |
| `product_snapshot` | 상품 스냅샷: `productId` · `speciesId` · `sellerId` · `status` · `hidden` · `product_version`. 모든 상태를 저장 (최근 구매 상품을 품종으로 바꿀 때 `SOLD_OUT`도 씀) → [ADR 0021](../decisions/0021-product-event-payload-ai-snapshot.md) |

## 외부 API

챗봇 경로 (⬜ 미정, Gateway 응답 20초) → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

| API | 부르는 서비스 | 상태 |
|---|---|---|
| `/internal/v1/recommendations` | market (장바구니 추천) | 🟨 제안 |

상품 의미 검색은 범위 밖이라 product 호출이 없습니다. 상세 → [내부 API 목록](../reference/internal-apis.md)

## 호출하는 내부 API

없음. 상품 정보는 이벤트로 받고, 추천 결과의 카드 정보는 market이 product에서 조회합니다 → [ADR 0021](../decisions/0021-product-event-payload-ai-snapshot.md)

## 발행 이벤트

없음

## 구독 이벤트

| 이벤트 | 받아서 하는 일 | 멱등 처리 |
|---|---|---|
| `product.events.v1` `ProductRegistered` · `ProductUpdated` · `ProductStatusChanged` | payload로 상품 스냅샷 저장 · 갱신 | productId upsert, `productVersion`이 저장된 `product_version`보다 클 때만 반영 (같으면 중복) |

## 스케줄러

두지 않음 (2개까지 늘어나 중복 실행 위험)

## 정책

| 정책 | 내용 |
|---|---|
| 임베딩 모델 | OpenAI `text-embedding-3-small`, 1536차원. 차원을 바꾸면 벡터 칸과 저장된 벡터를 모두 다시 만듦 |
| 이미지 기반 추천 | 하지 않음 |
| 장애 시 | 추천 영역만 비고 장바구니 · 주문은 영향 없음. market이 기본 추천으로 대체 |

## 테스트 시나리오

⬜ 추가 예정 → [테스트 시나리오](../testing/scenarios.md)
