# 0024. 임베딩 모델은 OpenAI text-embedding-3-small (1536차원)

> 상태: ✅ 확정 · 결정일 2026-11-14 · 결정한 사람: ai 담당 (#290, PR #450)
> 대체: - · 대체됨: -

## 배경

[ADR 0005](0005-opensearch-pgvector-external-embedding.md)는 「임베딩은 외부 API」까지만 정했습니다. 로컬 모델은 메모리 부족으로 뺐지만, 어느 회사의 어떤 모델을 쓸지는 정하지 않았습니다. #290 To-do 「임베딩 모델 설정 (모델 · 차원 결정)」에 따라 PR #450에서 모델을 정했으나 이유를 남기지 않아, 2026-11-16에 사후 기록으로 이 ADR을 씁니다.

## 결정

ai의 품종 임베딩은 OpenAI `text-embedding-3-small`, 1536차원(이 모델의 기본 차원)으로 합니다. 키는 `OPENAI_API_KEY`입니다.

## 고른 이유

| 이유 | 내용 |
|---|---|
| 계정 · 결제를 하나로 | product가 사진 검수에 이미 OpenAI Moderation을 씁니다([ADR 0012](0012-review-moderation-haiku.md)). 같은 `OPENAI_API_KEY`로 계정 · 결제를 함께 관리합니다 |
| Anthropic은 임베딩 없음 | 사진 검수의 Claude Haiku를 만든 Anthropic은 자체 임베딩 모델을 제공하지 않습니다(공식 문서는 Voyage AI를 안내) |
| Spring AI 기본 지원 | `spring-ai-starter-model-openai` 스타터만 넣으면 임베딩 모델이 자동 설정됩니다. 채팅 · 검열 등 다른 모델은 설정에서 끔 |
| 가장 쌈 | 1M 토큰당 `text-embedding-3-small` $0.02, `text-embedding-ada-002` $0.10, `text-embedding-3-large` $0.13 (2026-11-16 OpenAI 가격표 기준). 품종 215개 규모라 비용이 미미합니다 |
| 인덱스 한도 안 | pgvector HNSW 인덱스는 `vector` 타입을 2,000차원까지 지원합니다. 1536차원은 그대로 인덱스를 만들 수 있습니다 |

## 다른 안

| 안 | 버린 이유 |
|---|---|
| OpenAI `text-embedding-3-large` (3072차원) | 6.5배 비쌈, 기본 차원이 HNSW 한도(2,000)를 넘어 차원을 줄이거나 `halfvec`을 써야 함 |
| Voyage AI 등 다른 회사 | 계정 · 키 · 결제가 하나 더 늘어남 |
| 로컬 임베딩 모델 (예: multilingual-e5-small · bge-m3) | 메모리 부족으로 뺐음 ([ADR 0005](0005-opensearch-pgvector-external-embedding.md)). 다만 추천할 때는 모델을 부르지 않고 재임베딩(배치) 때만 쓰므로 다시 볼 여지가 있음 → 비교 예정 (아래 「영향」) |

## 영향

[ai](../services/ai.md) 「정책」 임베딩 모델, [장애 대응](../conventions/resilience.md) 호출별 값. 모델 · 차원을 바꾸면 `species_vector_store`의 `vector(1536)` 칸과 저장된 벡터를 모두 다시 만들어야 합니다.

로컬 임베딩 모델 비교 예정: 추천 기능(#293 → #294 → #296)을 먼저 완성하고, #294에서 만드는 평가 세트(같이 키우기 좋은 조합 30~50쌍, Top-5 적중률)로 OpenAI와 로컬 모델을 오프라인에서 비교합니다(운영 코드는 건드리지 않음, 근거: 핀 문서 「상품 정책·플로우 정리」 추천 절). 이 결정은 확정이지만, 비교에서 로컬 모델이 더 좋으면 교체하고 이 ADR을 새 ADR로 대체합니다.
