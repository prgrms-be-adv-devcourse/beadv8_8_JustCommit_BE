# 0020. 품종 임베딩은 ai가 자체 시드로, species.events.v1은 발행하지 않음

> 상태: ✅ 확정 · 결정일 2026-11-12 · 결정한 사람: product · ai 담당 (#338 · #335)
> 대체: 0005 (품종 임베딩 입력 부분만) · 대체됨: -

## 배경

[ADR 0005](0005-opensearch-pgvector-external-embedding.md)는 품종 임베딩 입력을 product가 발행하는 `species.events.v1`(`SpeciesProfileUpdated`)로 정했습니다. #338에서 발행 코드를 만들려고 보니 세 가지가 맞지 않았습니다.

| 문제 | 내용 |
|---|---|
| 발행할 곳이 없음 | 품종은 Flyway(V3 · V4)로만 바뀌고 바꾸는 API가 없습니다. 보내려면 "전체 1회 발행" 내부 API를 새로 만들어야 합니다 |
| 보낼 정보가 부족함 | product `SPECIES`에는 광도 · 물주기 · 난이도 · 잎 색 · 분류만 있습니다. ai 품종 문서에 필요한 배치 장소 · 온도 · 특징 · 관리 팁이 없습니다 |
| ai 이슈와 다름 | ai #292는 이미 농사로 자료로 ai DB에 `species_profile` 시드를 넣고 재임베딩 운영 API를 두는 방식입니다 |

## 결정

ai가 농사로 자료로 자기 DB에 품종 프로필을 시드하고 직접 임베딩합니다 (#292). product는 `species.events.v1`을 발행하지 않습니다. 품종 ID는 product `species.id`와 같게 둡니다.

## 고른 이유

ai에 필요한 생육 정보가 product에 없어서, 이벤트를 만들어도 ai는 결국 자기 시드가 필요합니다. 품종이 바뀌지 않는 고정 데이터라 이벤트로 동기화할 일이 없고, product에 내부 API · 발행 코드를 만들지 않아도 됩니다.

## 다른 안

| 안 | 버린 이유 |
|---|---|
| product가 `SpeciesProfileUpdated` 발행 + 전체 발행 내부 API | 발행할 계기가 없고, ai에 필요한 생육 정보를 보낼 수 없음 |
| product `SPECIES`에 생육 정보 칸 추가 후 발행 | 개발 종료(11/20) 전 product 범위가 커지고, 쓰는 곳은 ai뿐 |

## 영향

- [Kafka 토픽 표](../reference/kafka-topics.md): `species.events.v1`은 발행 안 함. `KafkaTopics.SPECIES_EVENTS` 상수 삭제는 common-messaging 담당과 정함 (⬜)
- [검색 · 추천 흐름](../flows/search-recommend.md) 「품종 임베딩」, [ai](../services/ai.md), [product](../services/product.md) 발행 이벤트
- 품종 ID가 product와 같아야 합니다. product는 215종(판매 금지 2종 제외)이고 #292 시드 초안은 217종이라, 맞추는 일은 #292에서 합니다 (⬜)
