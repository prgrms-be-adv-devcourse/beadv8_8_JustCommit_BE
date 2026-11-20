# ai-service

> 언제 읽나: ai-service 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-20 · 관련: Miro 5 · 17, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 품종 임베딩 | 자체 시드한 품종 프로필로 품종 문서(품종명 + 별칭 + 학명 + 생육 정보)를 만들어 외부 API로 임베딩 → pgvector ([ADR 0020](../decisions/0020-species-profile-ai-seed.md)) |
| 추천 | 장바구니에 담은 품종과 생육 조건이 비슷한 품종의 판매 상품 ("같이 키우기 좋은 식물"). 상품은 상품 스냅샷에서 판매 중만 고름 ([ADR 0021](../decisions/0021-product-event-payload-ai-snapshot.md)) |
| 품종 의미 검색 | 사용자가 쓴 문장을 임베딩해 pgvector에서 가까운 품종과 그 품종의 판매 중 상품을 찾음 ([ADR 0030](../decisions/0030-species-semantic-search-pgvector.md)) |
| 챗봇 | 🟨 질문 문장을 임베딩해 판매 중 상품 문서 · 품종 문서를 유사도 검색하고, 찾은 문서만 근거로 Claude Haiku가 답함(RAG). 대화는 conversationId로 이어감 (#297, 별도 브랜치 · 머지는 팀이 정함) → [ADR 0031](../decisions/0031-chatbot-rag-product-document-payload-v3.md) |
| 상품 문서 임베딩 | 🟨 상품 이벤트(payload v3)로 상품 문서(제목 · 품종 특징 · 설명 · 키우기 메모 · 크기)를 만들어 `product_vector_store`에 임베딩. 문서가 바뀔 때만 (#298) |

흐름 → [검색 · 추천](../flows/search-recommend.md). K3s, HPA CPU 70% 1→2개 → [배포](../infra/deploy.md)

## 소유 테이블

DB `ai_db` · 계정 `ai_app` (pgvector 확장 `vector`, Flyway V1) → [DB · Flyway](../infra/database.md)

| 테이블 | 설명 |
|---|---|
| `species_profile` | 농사로 자료로 시드한 품종 프로필 215종 (품종 ID = product `species.id`, Flyway V2 테이블 · V3 시드). 시드 원본은 팀 드라이브 `nongsaro/species_profile_seed.csv` · `generate_ai_seed.py` → [ADR 0020](../decisions/0020-species-profile-ai-seed.md) |
| `species_vector_store` | 품종 벡터 (pgvector, `vector(1536)` · HNSW 코사인): 품종 문서 임베딩, `id` = speciesId, `metadata.documentHash`로 재임베딩 판단 |
| `product_snapshot` | 상품 스냅샷 (Flyway V4, `(species_id, status)` 인덱스): `productId` · `speciesId` · `sellerId` · `status` · `hidden` · `product_version`, 챗봇용 `title` · `price` · `thumbnail_url` · `description` · `grow_note` · `height_cm` · `pot_size`(V5, payload v2로 저장된 행은 `NULL`). 모든 상태를 저장 (최근 구매 상품을 품종으로 바꿀 때 `SOLD_OUT`도 씀) → [ADR 0021](../decisions/0021-product-event-payload-ai-snapshot.md) |
| `product_vector_store` | 🟨 상품 벡터 (V5, `vector(1536)` · HNSW 코사인): 상품 문서 임베딩, `id` = productId, `metadata.documentHash`로 재임베딩 판단. 판매 중 · 숨김은 두지 않고 검색할 때 `product_snapshot`과 조인 |
| `chat_conversation` · `chat_message` | 🟨 챗봇 대화 주인(V6, conversationId UUID · 회원 ID)과 질문 · 답변 기록. 30일 정리 배치는 ⬜ 미구현 |

## 외부 API

| API | 권한 | 상태 |
|---|---|---|
| `GET speciesSearches` | 공개 (Gateway IP 요청 수 제한) | ✅ |
| `POST chatbot/messages` | 로그인 (Gateway 응답 20초 · 회원 ID 요청 수 제한) | 🟨 |

상세 → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

| API | 부르는 서비스 | 상태 |
|---|---|---|
| `POST /internal/v1/recommendations/cart` | market (장바구니 추천) | ✅ |
| `POST /internal/v1/ai/admin/reindex?target=SPECIES` | 운영자 (재임베딩) | ✅ |
| `POST /internal/v1/ai/admin/reindex?target=PRODUCT` | 운영자 (상품 재임베딩) | 🟨 |

product → ai 호출은 없습니다 (상품 의미 검색은 챗봇이 맡음). 상세 → [내부 API 목록](../reference/internal-apis.md)

## 호출하는 내부 API

없음. 상품 정보는 이벤트로 받고, 추천 결과의 카드 정보는 market이 product에서 조회합니다 → [ADR 0021](../decisions/0021-product-event-payload-ai-snapshot.md)

## 발행 이벤트

없음

## 구독 이벤트

| 이벤트 | 받아서 하는 일 | 멱등 처리 |
|---|---|---|
| `product.events.v1` `ProductRegistered` · `ProductUpdated` · `ProductStatusChanged` | payload로 상품 스냅샷 저장 · 갱신 → 저장된 스냅샷으로 상품 문서 임베딩(문서 해시가 바뀔 때만, 삭제면 벡터 삭제) | productId upsert, `productVersion`이 저장된 `product_version`보다 클 때만 반영 (같으면 중복). 임베딩은 저장된 최신 스냅샷 기준이라 재시도 · 늦은 이벤트에도 결과가 같음. 임베딩 실패는 1 · 2 · 4초 재시도 후 DLT(스냅샷은 이미 저장됨) |

## 스케줄러

두지 않음 (2개까지 늘어나 중복 실행 위험)

## 정책

| 정책 | 내용 |
|---|---|
| 임베딩 모델 | OpenAI `text-embedding-3-small`, 1536차원. product 검수와 같은 OpenAI 키 · Anthropic은 임베딩 없음 · Spring AI 기본 지원 · OpenAI 임베딩 중 가장 쌈 → [ADR 0024](../decisions/0024-embedding-openai-text-embedding-3-small.md) (#290, PR #450). 모델 · 차원은 `custom.embedding.model` · `custom.embedding.dimensions` 한 곳에서 정하고 Spring AI 설정도 이 값을 읽음 → [환경변수](../reference/env-vars.md) (#508). 차원을 바꾸면 벡터 칸과 저장된 벡터를 모두 다시 만듦. 설정 차원이 `vector(1536)` 칸과 다르면 앱 시작이 실패함. 로컬 임베딩 모델(`multilingual-e5-small`)은 서버 메모리 실측(int8도 재임베딩에 약 2GB, 지금 ai 파드 상한 640Mi에서 시작 중 OOM) 결과 비교에서 제외 (#507) |
| 이미지 기반 추천 | 하지 않음 |
| 장애 시 | 추천은 빈 목록(화면은 최신 상품으로 대신), 장바구니 · 주문은 영향 없음. 품종 의미 검색은 OpenAI 실패 · 시간 초과면 503. 챗봇은 질문 임베딩 실패면 503 `CHATBOT_UNAVAILABLE`, LLM 실패 · 키 없음이면 200 고정 문장 + 상품 카드(`answerGenerated=false`) → [장애 대응](../conventions/resilience.md) |
| 챗봇 검색 | 🟨 상품 Top 6(판매 중 · 숨김 아님, 유사도 0.28 미만은 뺌, 11/20 실제 키로 관계없는 질문 최고 0.261 · 관계있는 질문 상위 0.30~0.39) · 품종 문서 Top 3(품종 의미 검색과 같은 쿼리 · 최소 유사도 0.35, `custom.species-search.min-similarity`). 질문 임베딩도 품종 의미 검색의 `QueryEmbedder`(3초 · 재시도 없음)를 같이 씀. 이어 묻는 질문은 직전 질문을 앞에 붙여 검색(LLM으로 질문을 다시 쓰지 않음). 값은 `custom.chatbot.*` |
| 챗봇 답변 | 🟨 Claude Haiku 5.5(`anthropic-java`, effort LOW, 재시도 없음, 10초). 찾은 문서 밖 내용 · 독성 근거 없는 「안전」 금지, 추천은 판매 중 상품에서만(품종 문서는 설명 근거), 「그중」 질문은 바로 앞 답변에서 이름을 말한 상품 중에서만(그 상품 정보가 없으면 모른다고 답함), 식물 밖 질문 거절, 맞는 품종 문서가 없거나 질문한 식물과 이름이 다르면 그 식물은 목록에 없다고 답함, 근거 품종을 상품이라 부르지 않음, 2~3문장. 마크다운 강조(`**` · `__`)는 서버가 지움. 검색 결과는 이번 질문에만 붙이고 기록에는 질문 원문만 저장 → [ADR 0031](../decisions/0031-chatbot-rag-product-document-payload-v3.md) |
| 챗봇 품질 점검 | 🟨 11/20 대표 질문 18개(뜻으로 찾기 8 · 판매 완료 · 숨김 2 · 목록 밖 · 판매 없는 품종 3 · 범위 밖 · 주입 3 · 반려동물 안전 1 · 이어 묻기 1), 실제 키 · 시연 상품 12개(판매 중 10). 응답 1.3 ~ 3.7초, 판매 완료 · 숨김 상품 0건, 목록 밖 · 범위 밖 · 주입은 모두 거절, 뜻으로 찾기 8개 모두 맞는 상품으로 답함. 점검 뒤 고친 것: 마크다운 강조 제거, 「그중」 질문 규칙. 남은 한계: 상품 문서도 같은 양식이라 유사도가 0.28 ~ 0.40에 몰려 정답 상품이 5위인 경우가 있고(하한을 올리면 정답이 빠져 0.28 유지), 답변에 안 나온 상품 카드도 붙음 (#303) |
| 챗봇 대화 | 🟨 conversationId가 없으면 새로 발급, 다른 회원 대화 403 · 없는 대화 404, 프롬프트에는 최근 10개 메시지. 질문 1~300자 |
| 챗봇 비용 보호 | 🟨 로그인 필수 + Gateway 회원당 1분 10회(429), 메시지마다 응답 시간 · 모델 호출 수 로그 |

## 테스트 시나리오

⬜ 추가 예정 → [테스트 시나리오](../testing/scenarios.md)
