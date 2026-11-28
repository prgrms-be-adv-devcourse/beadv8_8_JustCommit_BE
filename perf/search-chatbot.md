# 검색 · 챗봇 측정 (#634 · #636)

상품 키워드 검색 · 품종 의미 검색 · 챗봇의 단계별 운영 측정과 의미 검색 속도 개선 전후. 도구 · 폴더 설명은 [부하 측정](README.md).

## 검색 · 챗봇 단계별 운영 측정 (#634)

운영에서 상품 키워드 검색 · 품종 의미 검색 · 챗봇이 어느 단계에서 시간을 쓰는지 잰다. 한계점이 아니라 평소 한 요청의 단계별 p50 · p95를 보는 측정이라 부하는 낮게 둔다. 이 결과로 #635(답변 스트리밍) · #636(임베딩 캐시 · 병렬화)에서 무엇부터 고칠지 정하고, 개선 뒤 같은 입력(`k6/lib/search-stages-data.js`)으로 다시 재서 비교한다.

### 단계별 지표

서비스가 `/actuator/prometheus`로 내보내는 Timer (태그 `stage` · `outcome`, 구간 히스토그램). Grafana 「replant MSA 측정」의 「검색 · 챗봇 단계별 시간」 줄에 단계별 성공 p50 · p95와 실패 p95가 나온다.

| 지표 | 단계 `stage` | 남기는 때 | 구간 범위 |
|---|---|---|---|
| `product_search_stage_seconds` (product) | `total` 전체 · `opensearch` product가 본 OpenSearch 호출 · `opensearch_took` OpenSearch 응답의 `took` · `db` DB 재조회 · `fallback` 장애 시 LIKE | 키워드 검색만 (키워드 없는 목록 · 판매자 상점은 안 남김). LIKE 대체로 응답했으면 `total`은 성공, `opensearch`는 실패 | 1ms ~ 5초 |
| `ai_species_search_stage_seconds` (ai) | `total` · `embedding` 질문 임베딩(OpenAI) · `vector_search` 품종 벡터 검색 · `product_lookup` 판매 중 상품 조회 | 입력 검증(400)을 통과한 요청. 임베딩 실패(503)는 `embedding` · `total` 실패 | 5ms ~ 5초 |
| `ai_chatbot_stage_seconds` (ai) | `total` · `history` 대화 기록(이어 묻기만) · `embedding` · `product_search`(대안 포함) · `species_search` · `answer` 답변 생성(Claude) · `save` 대화 저장 | `answer`는 키가 있을 때만. 답변 생성 실패는 고정 문장으로 답하므로 `answer`만 실패, 404 · 403 · 503은 `total`도 실패 | 5ms ~ 30초 |

- `outcome`: `success` · `failure`. 실패는 예외로 끝난 단계에 걸린 시간 (시간 초과로 실패한 요청이 어느 단계에서 기다렸는지 보려고, #649 리뷰). 성공 Timer는 시작할 때 만들고 실패 Timer는 처음 실패할 때 생긴다
- 구간 범위: 히스토그램 구간을 범위 안만 내보내 시계열 수를 줄인다 (기본 1ms ~ 30초). 범위 밖 값은 맨 끝 구간에 들어가 p95가 범위 끝으로 보인다

- `opensearch` − `opensearch_took` = 네트워크 · 연결 풀 · 동시 검색 자리 대기 · 직렬화. 이 차이가 크면 OpenSearch 밖의 문제다
- `answer`는 운영 ai에 `ANTHROPIC_API_KEY`가 있어야 남는다. 없으면 챗봇이 고정 문장으로 답하고(`answerGenerated=false`) 이 단계는 0건

### 운영 리소스 (측정 전에 확인)

| 대상 | 설정 (레포 기준) | 운영에서 확인 |
|---|---|---|
| product | compose `mem_limit: 768m`, CPU 한도 없음 | `docker stats --no-stream replant-product` |
| ai | K3s requests `cpu: 200m` · `memory: 640Mi`, limits `memory: 640Mi`, HPA 1 ~ 2 | `kubectl -n replant top pod -l app=ai`, `kubectl -n replant get hpa ai` |
| OpenSearch | 힙 `-Xms512m -Xmx512m`, `mem_limit: 1200m` (데이터 서버) | `curl -s localhost:9200/_nodes/stats/jvm?filter_path=**.heap_used_percent` |
| 앱 서버 · 데이터 서버 CPU | t3.large · t3.medium (steal 오르면 측정 무효) | Grafana 「서버 CPU」 · 「CPU steal」 |

### 측정 입력

| 대상 | 입력 (`k6/lib/search-stages-data.js`) | 속도 |
|---|---|---|
| 상품 키워드 검색 | 검색어 21개 (품종 이름 · 두 단어 · 오타 · 별칭 · 학명 · 설명 낱말 · 결과 없음) | 분당 120 × 5분 |
| 품종 의미 검색 | 상황 문장 10개 | 분당 9 × 6분 (Gateway가 IP당 1분 10회로 제한) |
| 챗봇 | #303 대표 질문 18개 (「그중에 제일 작은 건 뭐야?」는 앞 질문에 이어 묻기) | 회원 3명 × 18개, 회원마다 7초 간격 (회원당 1분 10회 제한) |

### 결과

`analysis/stage_timing.py` 출력 (운영 Prometheus를 Grafana 데이터 소스 프록시로 조회, 2026-11-27). 실패는 세 측정 모두 0건, 상품 검색 LIKE 대체(`fallback`)도 0건.

| 대상 | 측정 (한국 시간) | 건수 | 전체 p50 · p95 ms | 가장 느린 단계 (p95) |
|---|---|---|---|---|
| 상품 키워드 검색 | 2026-11-25 17:31:43 ~ 17:36:43 | 599 | 11 · 33 | `opensearch` 22ms |
| 품종 의미 검색 | 2026-11-25 17:36:51 ~ 17:42:52 | 53 | 474 · 584 | `embedding` 531ms |
| 챗봇 | 2026-11-27 19:37:39 ~ 19:43:03 | 36 | 1,476 · 4,438 | `answer` 2,219ms |

| 상품 키워드 검색 단계 | p50 ms | p95 ms |
|---|---|---|
| `total` | 11 | 33 |
| `opensearch` | 6 | 22 |
| `opensearch_took` | 2 | 16 |
| `db` | 5 | 13 |
| HTTP product | 15 | 39 |

| 품종 의미 검색 단계 | p50 ms | p95 ms |
|---|---|---|
| `total` | 474 | 584 |
| `embedding` | 413 | 531 |
| `vector_search` | 12 | 117 |
| `product_lookup` | 3 | 5 |
| HTTP ai | 476 | 584 |

| 챗봇 단계 | p50 ms | p95 ms |
|---|---|---|
| `total` | 1,476 | 4,438 |
| `history` (이어 묻기 2건) | 7 | 22 |
| `embedding` | 163 | 603 |
| `product_search` | 22 | 54 |
| `species_search` | 59 | 95 |
| `answer` | 1,217 | 2,219 |
| `save` | 7 | 17 |
| HTTP ai | 1,456 | 2,419 |

- 챗봇은 측정 계정 대신 회원 1명으로 18개 질문을 두 번 보냈다 (`EMAIL=… k6 run -e ROUNDS=2 chatbot.js`). 답변 생성 36건 중 36건. 지표 건수(37 · 38)는 `increase` 외삽이라 보낸 36건보다 조금 많다. k6가 본 응답 p95는 4.16초
- 상품 키워드 검색은 p95 33ms로 느린 단계가 없다. `opensearch` − `opensearch_took`(p50 약 4ms)이 OpenSearch 밖 시간이라 연결 · 직렬화 부담도 작다
- 품종 의미 검색은 전체의 약 87%(p50 413 / 474ms)가 질문 임베딩(OpenAI 호출)이다 → #636(임베딩 캐시 · 병렬화)은 임베딩부터 줄인다. `vector_search` p95 117ms는 p50 12ms보다 크게 튀어 53건 중 몇 건의 값이라 참고만 한다
- 챗봇은 p50 기준 전체의 약 82%(1,217 / 1,476ms)가 답변 생성(Claude)이다 → #635(답변 스트리밍)가 체감 시간을 가장 크게 줄인다. 다음은 질문 임베딩(p95 603ms)으로, 품종 의미 검색과 같은 OpenAI 호출이라 #636이 함께 줄인다. 건수가 36건이라 p95는 참고만 한다

### 다시 재기

```bash
cd perf/k6
# 1) 상품 키워드 검색 (로그인 없음)
k6 run -e TARGET=product -e RPM=120 -e DURATION=5m --summary-export results/stages-product-$(date +%Y%m%d-%H%M).json search-stages.js
# 2) 품종 의미 검색 (로그인 없음, 분당 9)
k6 run -e TARGET=species -e RPM=9 -e DURATION=6m --summary-export results/stages-species-$(date +%Y%m%d-%H%M).json search-stages.js
# 3) 챗봇 (perf-buyer-1..3 로그인, 비밀번호 ~/.replant-perf/password)
./run.sh chatbot.js -e MEMBERS=3

# 측정마다 시작 · 끝 시각으로 단계별 표 (공개 Grafana를 거쳐 운영 Prometheus 조회)
python3 -I ../analysis/stage_timing.py --grafana https://replant.kro.kr/grafana --start <시작> --end <끝> --only product
```

- 조회에는 Grafana 서비스 계정 토큰이 환경변수 `GRAFANA_SERVICE_ACCOUNT_TOKEN`에 있어야 한다 → `docs/infra/observability.md` 「Claude Code로 로그 조회 · 요약」. SSH 터널(`ssh -N -L 19090:10.0.7.79:9090 replant-data`)이 있으면 `--grafana` 없이 조회한다
- `run.sh`는 k6 지표를 터널 주소(`localhost:19090`)로 보내려 한다. 터널이 없으면 그 전송만 실패하고, 단계별 표는 서비스 지표라 그대로 나온다

- 측정 계정(`perf-buyer-N@perf.test`)이 운영에 있어야 챗봇을 잴 수 있다. #628 뒤 시연 데이터로 되돌렸으면 `data/msa/seed.sh`로 다시 넣는다 (회원과 함께 상품 · 주문도 들어가므로 측정 뒤 시연 데이터로 되돌림)
- 배포 · 재시작 직후는 JVM이 덜 데워져 느리게 나온다 → 1분 데우고 잰다 (MSA 운영 측정과 같음)

### 챗봇 측정의 데이터 · 비용

| 항목 | 내용 |
|---|---|
| 계정 | 측정 전용 `perf-buyer-1..N@perf.test` (`data/msa/seed.sh`가 넣음). 실제 회원 · 시연 계정은 쓰지 않는다 |
| 남는 데이터 | 메시지마다 `ai_db`의 `chat_conversation` · `chat_message`에 대화가 저장된다 (`save` 단계). 측정 1번에 대화 51개 · 메시지 108개 (18개 중 이어 묻기 1개) |
| 정리 | `data/msa/clean-chatbot.sh`로 개수 확인 → `--yes`로 측정 계정 대화만 삭제 (`chat_message`는 FK로 같이 지워짐) |
| 비용 | 1번 측정에 OpenAI 임베딩 54번 + Claude Haiku 답변 54번 (키가 있을 때). 측정한 날짜 · 횟수를 결과 표 옆에 적는다 |

## 의미 검색 속도 개선 전후 (#636)

#634 결과(위 「결과」, PR #693)에서 품종 의미 검색 시간의 약 87%가 질문 임베딩(OpenAI)이었다. 그래서 같은 질문은 다시 임베딩하지 않게 캐시를 두고, 챗봇의 상품 검색 · 품종 검색을 동시에 한다. 개선 뒤 같은 입력(`k6/lib/search-stages-data.js`) · 같은 속도로 다시 재 이 표를 채운다.

### 바꾼 것

| 항목 | 내용 |
|---|---|
| 질문 임베딩 캐시 | 키 = 모델 · 차원 · 정리한 질문(앞뒤 공백 제거, 연속 공백 하나로)의 SHA-256, 값 = 벡터. ai 파드 메모리(Caffeine) 1,000개 · 24시간 (`custom.query-embedding-cache.*`). 범위를 나눔: 비로그인 품종 의미 검색은 공용, 챗봇은 회원마다(공개 검색의 응답 시간으로 남의 챗봇 질문을 짐작하지 못하게, #694 리뷰). 챗봇 이어 묻기는 「직전 질문 + 이번 질문」 문장이 키 |
| 캐시 지표 | `ai_query_embedding_cache_total{result="hit"\|"miss"}`, `ai_query_embedding_cache_size`. `embedding` 단계 Timer는 적중 때도 남아(짧게) 전후 p50 · p95를 그대로 비교한다 |
| 챗봇 검색 동시 실행 | `product_search` · `species_search`를 가상 스레드 두 개에서 동시에. 상품 검색이 실패하면 바로 응답하고, 품종 검색은 2초(`custom.chatbot.species-search-timeout`)까지만 기다림. 두 검색을 묶은 시간은 새 단계 `search` (두 단계와 겹쳐 「가장 느린 단계」 비교에서는 뺌) |
| pgvector | 바꾸지 않음. 아래 「HNSW 확인」 |

Redis가 아니라 파드 메모리에 둔 이유: ai는 Redis를 쓰지 않고, 공용 Redis는 `maxmemory 256MB` · `noeviction`이라 벡터(약 6KB)가 쌓이면 로그인 토큰 쓰기까지 실패한다 (`docs/reference/redis-keys.md` 「설정」). 파드가 2개로 늘거나 재시작하면 캐시를 따로 · 처음부터 채우지만, 없으면 원래대로 OpenAI를 부를 뿐이다.

### HNSW 확인 (2026-11-27, 로컬 `ai_db`, pgvector 0.8.7)

| 쿼리 | 실행 계획 | 시간 |
|---|---|---|
| 품종 검색 (`SpeciesSearchQueryRepository.findSimilarSpecies`, 215개) | Seq Scan + Hash Join + top-N 정렬. `enable_seqscan=off`여도 HNSW가 아니라 기본 키 인덱스 | 1 ~ 10ms |
| 같은 표에서 `ORDER BY 거리 LIMIT`만 (조인 · 유사도 조건 없음) | 비용으로 Seq Scan 선택(43 대 866), `enable_seqscan=off`면 HNSW Index Scan | 0.5ms (Seq) · 18ms (HNSW 첫 실행) |
| 챗봇 상품 검색 (`ChatSearchRepository.findProducts`) | Seq Scan + Hash Join (로컬 상품 벡터 1개라 참고만) | 0.05ms |

- 지금 행 수(품종 215개, 상품도 많지 않음)에서는 플래너가 HNSW보다 전체 비교(정확한 결과)를 싸게 보고, 실제로도 몇 ms라 바꾸지 않았다. `hnsw.ef_search`(기본 40)는 HNSW를 타지 않으니 영향이 없다
- 두 쿼리 모두 유사도 조건 · 조인이 `ORDER BY 거리 LIMIT` 앞에 있어 행이 늘어도 HNSW를 쓸 수 없는 모양이다. 상품이 수천 개를 넘어 `vector_search` · `product_search`가 느려지면 「가까운 N개를 먼저 고르는 서브쿼리 → 조인 · 유사도 조건」으로 바꾼다(위 두 번째 줄 모양이면 HNSW를 탐). 판매 중 조건으로 걸러 개수가 모자라는 문제는 pgvector 0.8의 `hnsw.iterative_scan`으로 다룬다
- 운영 `vector_search` p95 117ms(p50 12ms)는 53건 중 몇 건이 튄 값이고 로컬 실행 시간과 차이가 커, 쿼리보다 DB 연결 · 버퍼 상태 쪽으로 본다. 개선 뒤 측정에서 다시 본다

### 결과

배포 뒤 측정. `analysis/stage_timing.py` 출력으로 채운다. 개선 전은 위 「결과」(#634).

| 대상 | 단계 | 개선 전 p50 · p95 ms | 개선 뒤 p50 · p95 ms |
|---|---|---|---|
| 품종 의미 검색 | `total` | 474 · 584 | |
| 품종 의미 검색 | `embedding` | 413 · 531 | |
| 챗봇 | `embedding` | 163 · 603 | |
| 챗봇 | `search` (개선 뒤만, 두 검색을 묶은 시간) | - | |
| 챗봇 | `total` | 1,476 · 4,438 | |
| 질문 임베딩 캐시 | 적중률 | - | |

- 품종 의미 검색 입력은 문장 10개를 분당 9번 6분(54건) 돌리므로, 배포 뒤 첫 바퀴 10건만 미적중이고 나머지는 적중한다. 「같은 질문을 다시 보냈을 때」의 시간이고, 실제 사용자 질문은 겹치는 비율이 이보다 낮다
- `embedding` Timer는 적중 · 미적중을 나누지 않는다. 미적중은 개선 전과 같은 OpenAI 호출이라, p50은 적중 시간, p95는 미적중이 5%보다 많으면 미적중 시간에 가깝게 나온다. 적중률을 함께 적는다
- 두 검색의 개선 전후는 `search`로 비교하지 않는다. 개선 전 지표는 단계별 히스토그램이라 요청마다 `product_search` + `species_search`를 더한 p95를 다시 계산할 수 없고, 단계 p95끼리 더한 값(54 + 95)은 느렸던 요청이 단계마다 달라 요청 기준과 다르다 (#694 리뷰). 요청 기준 비교는 요청마다 한 번 재는 챗봇 `total`로 하고, `search`는 개선 뒤 값만 적는다
- 챗봇 `answer`가 전체의 대부분이라 `total`은 크게 줄지 않는다(#635 답변 스트리밍이 맡음)

### 다시 재기

위 「다시 재기」(#634)와 같은 명령 · 입력. 배포 뒤 1분 데운 다음:

```bash
cd perf/k6
# 품종 의미 검색 (분당 9 × 6분)
k6 run -e TARGET=species -e RPM=9 -e DURATION=6m --summary-export results/stages-species-636-$(date +%Y%m%d-%H%M).json search-stages.js
# 챗봇 (18개 질문 두 바퀴: 두 번째 바퀴는 같은 질문이라 임베딩 캐시 적중)
EMAIL=<측정 회원> k6 run -e ROUNDS=2 chatbot.js

# 단계별 표 + 질문 임베딩 캐시 적중률 (토큰은 환경변수 GRAFANA_SERVICE_ACCOUNT_TOKEN)
python3 -I ../analysis/stage_timing.py --grafana https://replant.kro.kr/grafana --start <시작> --end <끝> --only species
python3 -I ../analysis/stage_timing.py --grafana https://replant.kro.kr/grafana --start <시작> --end <끝> --only chatbot
```

- `--grafana` · `EMAIL` · `ROUNDS`는 #634 결과 PR(#693)에서 들어온다. 그 PR이 머지되기 전이면 SSH 터널로 `--grafana` 없이 조회하고 챗봇은 `./run.sh chatbot.js -e MEMBERS=3`
- 캐시는 파드 메모리라 측정 전에 ai가 재시작되면 비어 있다. 파드가 2개면 적중률이 낮게 나올 수 있으니 `kubectl -n replant get pods -l app=ai`로 개수를 같이 적는다
