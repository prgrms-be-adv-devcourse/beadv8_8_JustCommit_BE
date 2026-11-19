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
| 로컬 임베딩 모델 (예: multilingual-e5-small · bge-m3) | 메모리 부족으로 뺐음 ([ADR 0005](0005-opensearch-pgvector-external-embedding.md)). 2026-11-18 #507에서 `multilingual-e5-small`을 서버 메모리로 실측한 결과 상주 불가라 비교에서 제외 (아래 「영향」) |

## 영향

[ai](../services/ai.md) 「정책」 임베딩 모델, [장애 대응](../conventions/resilience.md) 호출별 값. 모델 · 차원을 바꾸면 `species_vector_store`의 `vector(1536)` 칸과 저장된 벡터를 모두 다시 만들어야 합니다.

로컬 임베딩 모델은 비교하지 않기로 했습니다. #507에서 `multilingual-e5-small`(384차원, Spring AI ONNX)을 운영과 같은 ai 이미지 · Docker 메모리 상한으로 품종 215개 재임베딩 1회 실측했습니다([#507 코멘트](https://github.com/prgrms-be-adv-devcourse/beadv8_8_JustCommit_BE/issues/507#issuecomment-6971659177)). 지금 ai 파드 상한 640m에서는 int8(118MB)이 시작 중 OOM, 1536m에서는 재임베딩 중 OOM이었고 2g 이상에서야 성공하며 최고 1.96GiB를 썼습니다(fp32는 3g 이상에서만 성공, 2.6~3.3GiB). 재임베딩이 끝난 뒤에도 메모리가 약 2GiB로 유지되어 ai 파드에 상주시킬 수 없고, OpenAI 비용은 품종 전체 재임베딩에 1센트 미만이라 바꿀 이유가 없습니다. 결정은 그대로 OpenAI `text-embedding-3-small`을 유지하며 새 ADR로 대체하지 않습니다.
