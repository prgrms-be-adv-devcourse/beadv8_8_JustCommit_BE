# 0020. 품종 임베딩은 ai가 자체 시드로, species.events.v1은 발행하지 않음

> 상태: ✅ 확정 · 결정일 2026-11-12 · 결정한 사람: product · ai 담당 (#338 · #335)
> 대체: 0005 (품종 임베딩 입력 부분만) · 대체됨: -

## 배경

[ADR 0005](0005-opensearch-pgvector-external-embedding.md)는 품종 임베딩 입력을 product가 발행하는 `species.events.v1`(`SpeciesProfileUpdated`)로 정했습니다. #338에서 발행 코드를 만들려고 보니 세 가지가 맞지 않았습니다.

| 문제 | 내용 |
|---|---|
| 발행할 곳이 없음 | 품종은 Flyway(V3 · V4)로만 바뀌고 바꾸는 API가 없습니다. 보내려면 "전체 1회 발행" 내부 API를 새로 만들어야 합니다 |
| 보낼 정보가 부족함 | product `SPECIES`의 관리 정보는 필터 · 화면 표시용 **코드값 5개**뿐입니다 (아래 표). 임베딩 문서에 필요한 배치 장소 · 온도 · 습도 · 특징 같은 **설명 글**은 없습니다 |
| ai 이슈와 다름 | ai #292는 이미 농사로 자료로 ai DB에 `species_profile` 시드를 넣고 재임베딩 운영 API를 두는 방식입니다 |

### product에 있는 것과 ai에 필요한 것

농사로 실내정원용 식물 상세 응답(항목 이름은 농사로 API 그대로) 기준입니다.

| 정보 | 농사로 항목 | product `SPECIES` | ai 품종 문서 |
|---|---|---|---|
| 품종명 · 학명 · 별칭 | `plntbneNm` · `plntzrNm` | ✅ `name` · `scientific_name` · `SPECIES_ALIAS` | 씀 |
| 광도 | `lighttdemanddoCodeNm` (예: 중간 광도(800~1,500 Lux)) | ✅ `light` 코드 3단계 (`LOW` · `MEDIUM` · `HIGH`) | 원문 |
| 물주기 | `watercycleSprngCodeNm` 등 계절 4개 | ✅ `watering` 코드 1개 (계절 구분 없음) | 계절별 원문 |
| 난이도 | `managelevelCodeNm` (초보자 등) | ✅ `difficulty` 코드 | 씀 (#294 난이도 적합도) |
| 잎 색 · 분류 | `lefcolrCodeNm` · `clCodeNm` | ✅ `color` · `category` 코드 | 씀 |
| 배치 장소 | `postngplaceCodeNm` (예: 거실 창측, 발코니 내측) | ❌ | 씀 |
| 온도 | `grwhTpCodeNm` 생육 온도 · `winterLwetTpCodeNm` 겨울 최저 온도 | ❌ | 씀 (#294 환경 점수) |
| 습도 | `hdCodeNm` | ❌ | 씀 |
| 특징 · 관리 팁 | `fncltyInfo` · `speclmanageInfo` · `adviseInfo` · `dlthtsManageInfo` · `toxctyInfo` | ❌ | 씀 (임베딩 문장) |

product 코드값은 여러 값을 하나로 줄인 것이라(광도 여러 단계 → 1개, 계절 물주기 4개 → 1개) 비슷한 품종을 고르는 데 쓰기에는 정보가 부족합니다.

## 결정

ai가 농사로 자료로 자기 DB에 품종 프로필을 시드하고 직접 임베딩합니다 (#292). product는 `species.events.v1`을 발행하지 않습니다. 품종 ID는 product `species.id`와 같게 둡니다.

### ai가 품종 정보를 얻는 방법

| 단계 | 내용 |
|---|---|
| 원본 | 농사로 실내정원용 식물 OpenAPI 상세 응답. 팀 드라이브 `자료/데이터/nongsaro/raw/dtl_{농사로 번호}.xml`에 내려받아 둠 (product V4를 만든 `species.csv`와 같은 원본) |
| 시드 CSV | 위 XML에서 품종 ID · 이름 + 위 표의 「ai 품종 문서」 항목을 뽑아 만듦 (⬜ #292에서 작성, 저장소 밖 같은 폴더) |
| 시드 SQL | 시드 CSV → ai Flyway 버전 파일(`species_profile`). product V4처럼 기준 데이터는 고치지 않고 새 버전으로 바꿈 |
| 품종 ID 맞추기 | product V4의 고정 ID(1~217, 18 · 178 빠짐, 215종)를 그대로 씀. 농사로 번호 → 품종 이름 → product ID로 연결 (product V4도 같은 방식). #292 테스트로 ID · 이름이 같은지 확인 |
| 임베딩 | 품종 문서 → 외부 임베딩 API → `species_vector_store`, 재임베딩 운영 API(문서가 그대로면 건너뜀) |

## 고른 이유

ai에 필요한 설명 글(배치 장소 · 온도 · 습도 · 특징)이 product에 없어서, 이벤트를 만들어도 ai는 결국 농사로 원본에서 자기 시드를 만들어야 합니다. 품종은 바뀌지 않는 고정 데이터라 이벤트로 동기화할 일이 없고, product에 내부 API · 발행 코드를 만들지 않아도 됩니다.

## 다른 안

| 안 | 버린 이유 |
|---|---|
| product가 `SpeciesProfileUpdated` 발행 + 전체 발행 내부 API | 발행할 계기가 없고, product 코드값 5개로는 ai 문서를 만들 수 없음 |
| product `SPECIES`에 설명 글 칸 추가 후 발행 | 개발 종료(11/20) 전 product 범위가 커지고, 쓰는 곳은 ai뿐 |

## 영향

- [Kafka 토픽 표](../reference/kafka-topics.md): `species.events.v1`은 발행 안 함. `KafkaTopics.SPECIES_EVENTS` 상수 삭제는 common-messaging 담당과 정함 (⬜)
- [검색 · 추천 흐름](../flows/search-recommend.md) 「품종 임베딩」, [ai](../services/ai.md), [product](../services/product.md) 발행 이벤트
- 품종 데이터가 두 곳(product V4 · ai 시드)에 있습니다. 둘 다 같은 농사로 원본에서 만들고 품종이 바뀌지 않는 고정 데이터라 동기화하지 않습니다. 품종을 더하면 product V4 다음 버전과 ai 시드 다음 버전을 함께 추가합니다
- 원본 CSV에 판매 금지로 뺀 18 · 178번이 남아 있으므로, 시드 CSV를 만들 때 빼야 합니다
