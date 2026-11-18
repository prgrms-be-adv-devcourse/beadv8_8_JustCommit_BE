# 0005. 검색 OpenSearch, 벡터 pgvector, 임베딩 외부 API, 색인은 이벤트

> 상태: ✅ 확정 · 결정일 2026-11-03 · 결정한 사람: 팀 회의
> 대체: - · 대체됨: 0020

## 배경

킥오프 필수 항목에 검색 엔진과 AI 추천이 있고, 서버는 2대뿐입니다.

## 결정

검색은 OpenSearch, 벡터는 ai DB 안의 pgvector(Spring AI `PgVectorStore`), 임베딩은 외부 API입니다. 검색 색인은 `product.events.v1` → product 색인 리스너, 품종 임베딩은 `species.events.v1` → ai입니다. 대상 문서는 품종 217개, 추천은 품종 단위입니다.

## 고른 이유

pgvector는 추가 서버가 필요 없고, 외부 임베딩은 메모리를 쓰지 않습니다. 색인을 이벤트로 하면 OpenSearch 장애가 상품 저장을 막지 않습니다.

## 다른 안

| 안 | 버린 이유 |
|---|---|
| Elasticsearch | 라이선스 · 메모리. 킥오프 문구는 "ES에서 갈라진 오픈소스"로 설명 |
| 별도 벡터 DB | 서버 추가 필요 |
| 로컬 임베딩 모델 | 메모리 부족 |
| 저장할 때 직접 색인 | OpenSearch 장애가 저장 실패로 번짐 |
| 이미지 기반 추천 | 범위 밖 |

## 영향

[검색 · 추천 흐름](../flows/search-recommend.md), [ai](../services/ai.md)
