# 0031. 챗봇은 상품 · 품종 문서 의미 검색 + Claude Haiku 답변(RAG), 상품 문서는 상품 이벤트 payload v3로

> 상태: 🟨 제안 · 결정일 2026-11-20 · 결정한 사람: ai 담당 (#297, 별도 브랜치. 머지 여부는 팀이 정함)
> 대체: 0021 일부 (「제목 · 가격은 싣지 않음」) · 대체됨: -

## 배경

챗봇(#297)은 11/10 담당자가 팀을 떠나 「범위 보류」였습니다. 최종 프로젝트 필수 요건 「벡터DB를 활용한 문서 검색」(사용자 문장의 뜻을 벡터DB 유사도로 찾기)을 채우려고 별도 브랜치에서 구현합니다. 이슈 #298은 상품 설명 텍스트를 문서로 임베딩하라고 하지만, ai는 상품 이벤트 payload v2(품종 · 판매자 · 상태 · 숨김 · 버전)만 받아 제목 · 설명이 없었습니다([ADR 0021](0021-product-event-payload-ai-snapshot.md)). 답변을 만들 LLM도 정하지 않았습니다.

## 결정

| 항목 | 결정 |
|---|---|
| 상품 글 | product 상품 이벤트 3종의 payload를 v3로 올려 `title` · `price` · `thumbnailUrl` · `description` · `growNote` · `heightCm` · `potSize`를 더 싣는다. ai는 상품 스냅샷에 저장해 상품 문서와 상품 카드를 만든다 |
| 벡터 | 상품 문서를 `product_vector_store`(pgvector, HNSW 코사인)에 임베딩. 판매 중 · 숨김은 벡터에 두지 않고 검색할 때 스냅샷과 조인해 거른다 |
| 검색 | 질문을 같은 임베딩 모델로 바꿔 상품 6개(판매 중, 유사도 하한) · 품종 문서 3개를 찾는다. 질문 임베딩(3초 · 재시도 없음)과 품종 찾기(쿼리 · 최소 유사도)는 품종 의미 검색([ADR 0030](0030-species-semantic-search-pgvector.md))의 것을 같이 쓴다 |
| 답변 | Claude Haiku 5.5(`anthropic-java`)가 찾은 문서만 근거로 2~3문장. 실패하면 고정 문장 + 상품 카드 |
| 대화 | ai DB `chat_conversation` · `chat_message`, 최근 10개 메시지를 프롬프트에 넣음 |

## 고른 이유

| 이유 | 내용 |
|---|---|
| ai → product 호출을 늘리지 않음 | ADR 0021대로 ai는 이벤트로만 상품을 받습니다. 상품 이벤트는 이미 ai만 payload를 읽으므로(색인 리스너는 상품 ID만 씀) 칸을 더해도 다른 구독자에 영향이 없습니다 |
| 상태가 한 곳에 | 판매 중 여부를 벡터 metadata에 두면 상태 이벤트마다 metadata를 고쳐야 하고 어긋날 수 있습니다. 조인하면 스냅샷이 바뀌는 즉시 결과에서 빠지고, 상태만 바뀐 이벤트는 임베딩 모델을 부르지 않습니다 |
| Haiku | product 사진 검수(`HaikuPlantIdentifier`)와 같은 SDK · 모델 · 키라 운영에서 동작이 확인됐고 계정이 늘지 않습니다. 생각 깊이(`effort`)를 LOW로 둘 수 있습니다 |
| 대화 기록 직접 저장 | 표 2개 · JDBC로 끝나고, 주인 확인(403 · 404)과 같은 트랜잭션에 둘 수 있습니다. Spring AI 채팅 기록 저장소는 Spring AI 채팅 모델 없이 쓰기 어렵습니다 |

## 다른 안

| 안 | 버린 이유 |
|---|---|
| ai가 product 내부 API로 상품 글을 조회 | ADR 0021과 어긋남. 기존 일괄 조회 API에 설명 · 키우기 메모가 없음 |
| 품종 문서만 검색 (상품 문서 없음) | 판매자가 쓴 설명 · 크기로는 못 찾음 (품종 의미 검색 #570과 같은 범위) |
| 챗봇이 품종 검색 · 질문 임베딩을 따로 둠 | 같은 품종 벡터를 두 기준으로 찾게 되고, 전역 OpenAI 설정을 바꾸면 재임베딩 · 상품 이벤트 임베딩의 재시도까지 꺼짐 |
| Spring AI `ChatClient` + OpenAI 채팅 모델 | 레포에서 아직 쓰지 않는 모델을 새로 골라야 하고 운영에서 확인된 적 없음 |
| 벡터 metadata에 상태 · 숨김 (#298 처음 To-do) | 위 「상태가 한 곳에」 |
| Redis에 대화 기록 | ai에 Redis가 없음. 요청 수 제한은 Gateway(Redis 있음)에서 회원 ID로 셈 |

## 영향

- [Kafka 토픽](../reference/kafka-topics.md) 상품 이벤트 payload v3, [product](../services/product.md) 발행 칸
- [ai](../services/ai.md), [검색 · 추천](../flows/search-recommend.md) 「의미 검색」, [외부 API](../reference/external-apis.md), [내부 API](../reference/internal-apis.md) 재임베딩 `target=PRODUCT`
- [장애 대응](../conventions/resilience.md) ai → 임베딩 · Haiku, [Gateway](../services/gateway.md) 챗봇 요청 수 제한, [환경변수](../reference/env-vars.md) ai `ANTHROPIC_API_KEY`
- payload v2로 이미 저장된 상품은 다음 상품 이벤트(수정 · 상태 변경) 때 문서가 생깁니다. 한 번에 채우려면 product 「전체 상품 이벤트 재발행」(⬜ 미구현)이 필요하고, 시연은 상품을 새로 등록합니다
