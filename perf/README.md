# 부하 측정 (#351)

세미 모놀리스(Spring Modulith)가 어디서 무너지는지 숫자로 남기고, 나중에 같은 스크립트로 MSA를 재서 비교하려고 만든 도구.
결과 · 해석은 노션 「replant 세미 프로젝트 측정 기록」(측정 기록 DB에 측정 한 번 = 한 행).

| 폴더 | 무엇 |
|---|---|
| `monitoring/` | 내 PC에서 띄우는 Prometheus · Grafana(대시보드 21패널), 서버 지표는 SSH 터널로 수집 |
| `fakes/` | 택배 · 토스 대신 쓰는 WireMock 매핑, 응답 지연을 바꾸는 `set-delay.sh` (가설 4) |
| `data/` | 테스트 데이터 SQL (회원 · 상품 · 주문 · 가설별 데이터), 측정 사이 정리 방법 |
| `k6/` | 부하 시나리오 (Smoke · 둘러보기 · 주문 · 섞어서 · 동시 구매 · 내 주문 목록), 실행 스크립트, 결과 원본 |
| `analysis/` | k6 실행 시각으로 Prometheus에서 단계별 표를 뽑는 `stage_report.py`, 검색 · 챗봇 단계별 p50 · p95 표 `stage_timing.py` (#634) |

## 서버 쪽 설정은 이 PR에 없다

측정 대상 앱에는 지표 노출 같은 최소 수정이 필요하다. develop에 넣지 않고 실험 브랜치 `gasic37200/common-351-perf-semi-experiment`에만 있다.

- `micrometer-registry-prometheus`, `application-perf.yml`(관리 포트 8081 · 응답 시간 히스토그램 · 가짜 외부 서버 주소 · 자동 구매확정 1분)
- `docker-compose.prod.yml`: 프로필 `prod,perf`, `node-exporter` · `postgres-exporter` · `perf-fakes`(WireMock) 서비스, 커넥션 풀 크기 환경변수 `PERF_POOL_SIZE`

MSA를 잴 때도 같은 모양(관리 포트 · 히스토그램 · 가짜 외부 서버)을 서비스마다 붙이면 이 스크립트를 그대로 쓸 수 있다.

## 순서

1. 서버: 실험 브랜치로 배포 → `data/seed.sql` (+ 필요하면 다른 SQL)
2. 내 PC: `monitoring/` 띄우기 + SSH 터널, k6 설치(`winget install GrafanaLabs.k6`)
3. `k6/run.sh smoke.js`로 한 바퀴 확인 → 시나리오별 측정 → `analysis/stage_report.py`로 단계별 표

## 측정하며 배운 것

- Windows Docker 안의 k6는 지연을 만든다 (서버 p95 40ms를 0.8 ~ 2초로 잼) → PC에 직접 설치
- 앞 측정이 남긴 데이터(택배 서버에 쌓인 예약)가 다음 측정을 오염시킨다 → `data/README.md` 「측정 사이 정리」
- 긴 측정 중 PC가 절전하면 측정 · 터널이 끊기고 공인 IP가 바뀔 수 있다 → 절전 끄기
- 앱 설정을 바꿔 컨테이너를 다시 만들면 그 전 로그가 사라진다 → 재시작 전에 `docker logs`를 파일로

## 상품 검색 LIKE → OpenSearch 전후 (#571)

세미 `browse.js` 결과는 목록 · 상세 · 검색 · 품종을 합친 숫자라 검색만의 p95가 남아 있지 않다. 그래서 같은 기계에서 검색 방식만 바꿔 다시 쟀다. 결과 · 해석은 노션 「측정 기록」.

### 환경 (2026-11-20, 로컬)

| 항목 | 값 |
|---|---|
| 기계 | Apple M5 10코어 · 메모리 24GB (k6 · 앱 · DB · OpenSearch가 한 기계) |
| product | develop 82816b1f + 실험 커밋 028c29c6(아래), JDK 25, `-Xmx538m` (운영 컨테이너 768MB × 70%), 포트 18082 |
| DB | 로컬 PostgreSQL 17 안 측정 전용 DB `product_perf_db` (개발 DB와 분리) |
| OpenSearch | 측정 전용 컨테이너 (운영과 같은 이미지 2.19.2 · 힙 512MB · 메모리 1200MB, 127.0.0.1:9201) |
| 데이터 | `data/product-seed.sql`로 판매 중 상품 1만 → 3만 → 10만, 품종 216개에 고르게 |
| 부하 | `k6/search-steps.sh`: 데우기 30초 뒤 20 · 40 · 80 · 160 · 320 · 640 rps, 단계마다 1분 따로 실행 |

### LIKE로 바꾸는 법 (실험 브랜치 `jiwon/product-571-search-perf-experiment`, develop에 머지하지 않음)

`ProductSearchService`에 `perf.search.force-like`(환경변수 `PERF_FORCE_LIKE`)를 두고 true면 키워드도 DB LIKE로 보낸다. 프로필 `perf`는 Kafka 구독 · 검수 스케줄러를 끈다 (측정 DB에 개발 토픽 이벤트가 섞이지 않게). LIKE 모드에서 OpenSearch 질의 수가 늘지 않는 것으로 확인했다.

### 결과 요약 (판정 기준은 위와 같음: p95 2초 또는 에러 5% 초과)

| 상품 수 | 같은 부하 p95 (LIKE → OpenSearch) | 버틴 최대 단계 (LIKE / OpenSearch) |
|---|---|---|
| 1만 | 80 rps: 11ms → 11ms · 320 rps: 555ms → 36ms | 320 / 320 (640에서 처리 374 / 592 rps) |
| 3만 | 80 rps: 44ms → 11ms · 160 rps: 2.5초 → 8ms | 80 / 320 (그 위 LIKE 처리 약 155 rps에서 멈춤) |
| 10만 | 20 rps: 278ms → 24ms · 40 rps: 4.1초 → 18ms | 20 / 320 (LIKE 처리 약 40 rps에서 멈춤) |

- LIKE는 `%검색어%`라 인덱스를 못 타고 매번 상품 표 전체를 훑는다 → 상품 수에 비례해 느려진다. OpenSearch는 1만 → 10만에서 80 rps p95가 11 → 13ms로 거의 그대로
- 세미와 같은 1만 개에서는 둘의 차이가 작다. 전환 효과는 데이터가 늘수록 커진다
- OpenSearch 10만 개 320 rps는 첫 측정 p95 3.1초, 다시 재서 365ms (7만 개를 막 색인한 직후라 세그먼트 병합과 겹친 것으로 봄, 두 결과 모두 `k6/results/`에 있음)

### 색인 시간 · 무중단 재색인

| 측정 | 결과 |
|---|---|
| 전체 색인 `POST /api/v1/admin/products/searchIndex` | 1만 1.9초 · 3만 5.6초(새로 2만) · 10만 8.7초(새로 7만) · 10만 변경 없음 5.9초 |
| 무중단 재색인 `POST …/searchIndex/rebuilds` (10만) | 26초 · 17초 |
| 80 rps 검색 중 무중단 재색인 | 오류 0 · LIKE 대체 0 · p95 39ms (평소 13ms) |

### 장애 시 LIKE 대체 경로 (상품 1만)

| OpenSearch 상태 | 결과 |
|---|---|
| 멈춤 (연결 거부) | 20 ~ 160 rps 모두 오류 0, p50이 LIKE 강제보다 약 5ms 느림 (실패 2번 + 경고 로그) |
| 일시정지 (`docker pause`, 연결은 되고 응답 없음) | 요청 하나는 4초(읽기 2초 × 2번) 뒤 LIKE로 응답. 그러나 10 rps에서 처리 1.9 rps · 76%가 60초 초과, 30초 뒤 Tomcat 스레드 152/200 사용 |

응답 없는 장애에서는 대체 경로가 동작하지 못했다. OpenSearch 클라이언트 연결 풀이 기본값(서버당 5개)이고 풀에서 연결을 기다리는 시간에 제한이 없어, 요청이 연결을 4초씩 잡으면 초당 1 ~ 2건만 지나가고 나머지는 줄을 선다. 이대로 두면 product의 다른 API도 스레드를 못 얻어 멈출 수 있다.

### 한계

- 한 기계에서 부하 도구와 대상이 CPU를 나눠 쓴다. 절대값은 운영과 다르고, LIKE와 OpenSearch의 상대 비교로만 본다
- Gateway를 거치지 않고 product에 바로 보냈다
- 낮은 단계(20 rps)가 높은 단계보다 p50이 높게 나온다 (데우기 뒤에도 같음). 요청 간격이 길어 CPU · 연결이 쉬는 영향으로 보이며 비교는 같은 단계끼리 한다
- LIKE는 제목 · 품종 이름만, OpenSearch는 제목 · 품종 · 별칭 · 학명 · 설명 + 오타 허용까지 찾는다. 같은 기능의 속도 비교가 아니라 「전환 전 경로 vs 지금 경로」 비교다

### 다시 재기

```bash
# 측정 전용 DB · OpenSearch (개발 데이터와 분리)
docker exec justcommit-postgres psql -U justcommit -d postgres -c "CREATE DATABASE product_perf_db OWNER product_app"
docker run -d --name justcommit-opensearch-perf -p 127.0.0.1:9201:9200 -e DISABLE_SECURITY_PLUGIN=true -e DISABLE_INSTALL_DEMO_CONFIG=true -e bootstrap.memory_lock=false -e discovery.type=single-node -e OPENSEARCH_JAVA_OPTS="-Xms512m -Xmx512m" --memory 1200m replant-opensearch:2.19.2
# 실험 커밋으로 product jar를 만들어 DB_URL=jdbc:postgresql://localhost:5432/product_perf_db OPENSEARCH_URL=http://localhost:9201 SERVER_PORT=18082
#   --spring.profiles.active=perf 로 띄움 (LIKE는 PERF_FORCE_LIKE=true). JWT_PUBLIC_KEY는 측정용으로 따로 만든 키 (색인 API용 ADMIN 토큰 서명)
docker exec -i justcommit-postgres psql -U product_app -d product_perf_db -v products=10000 -v sellers=200 < perf/data/product-seed.sql
# ADMIN 토큰으로 POST /api/v1/admin/products/searchIndex 뒤
LABEL=opensearch-10k perf/k6/search-steps.sh 20,40,80,160,320,640
```

## MSA 운영 측정 (#628)

세미와 같은 k6 스크립트 · 판정 기준(p95 2초 또는 에러 5%)으로 MSA 운영 서버를 쟀다. 결과 · 해석은 노션 「replant 세미 프로젝트 측정 기록」 아래 하위 페이지 「MSA 운영 측정 — 세미 기준선과 비교 (#628)」.

### 환경 (2026-11-22, 운영)

| 항목 | 세미 | MSA |
|---|---|---|
| 서버 | t3.medium 1대 | 앱 서버 t3.large(vCPU 2) K3s + compose, 데이터 서버 t3.medium |
| 지표 | 내 PC Prometheus 15초 | 운영 Alloy → Prometheus 60초, SSH 터널 `localhost:19090` (k6도 여기로 remote write) |
| 단계 유지 | 2분 | 3분 (60초 수집이라 단계마다 표본 2개 이상) |
| 로그인 | 공개 주소 | Gateway가 같은 IP 로그인을 연속 5회로 제한 → 미리 로그인만 SSH 터널로 Gateway NodePort(`localhost:18080` → `10.0.7.76:30080`)에 직접, 계정마다 다른 `X-Forwarded-For` (`LOGIN_BASE_URL`). 측정 요청은 공개 주소 |
| 데이터 | `data/seed.sql` | `data/msa/seed.sh` — 같은 양을 서비스별 DB에 (회원 → 회원 복제본 → 지갑 → 상품 → 지난 주문) |

### 결과 요약

| 시나리오 | 세미 한계 | MSA 한계 | MSA p95 (한계) | MSA가 무너진 원인 |
|---|---|---|---|---|
| 둘러보기 (상품 1만) | 80~88 TPS | 80 rps | 144ms | 160에서 데이터 서버 CPU 97% + Gateway 2파드 OOMKilled |
| 주문 · 결제 | 5건/초 (교착) | 10건/초 | 198ms | 20에서 앱 서버 CPU 100% |
| 섞어서 90:10 | 초당 20 | 초당 20 | 1,002ms | 40에서 데이터 서버 CPU 100%, 장바구니 담기 43% 실패 |
| 동시 구매 50 × 10 | 통과 | 통과 | 796ms | 5xx 0 · 지갑 일치 |
| 내 주문 목록 (11만) | 306ms (인덱스 뒤) | 40 rps 통과 | 83ms | — |

- 상품 4만 개에서 둘러보기는 20~40 rps에서 무너졌다: 목록 쿼리 `order by created_at`에 인덱스가 없어 매번 전체 스캔 + 디스크 정렬
- 운영 추적 샘플링 100% → 부하 뒤 Tempo가 데이터 서버 CPU 67~72%를 10분 넘게 씀. 데이터 서버 CPU 수치에 섞여 있음

### 다시 재기

```bash
# 터널 두 개 (따로 띄워 둠)
ssh -N -L 18080:10.0.7.76:30080 replant-app          # 미리 로그인용 Gateway 직접
ssh -N -L 19090:10.0.7.79:9090 replant-data          # 운영 Prometheus

PERF_HASH='<bcrypt 해시>' perf/data/msa/seed.sh      # Docker가 있으면 PERF_HASH 없이 (htpasswd)
# 관리자(perf-admin@perf.test) 토큰으로 POST /api/v1/admin/products/searchIndex/rebuilds
perf/data/msa/park-products.sh park <주문용 첫 id> <끝 id>   # 둘러보기는 세미처럼 상품 1만 개로 (뒤에 unpark + rebuilds)
perf/k6/run.sh browse.js -e HOLD=3m
LOGIN_BASE_URL=http://localhost:18080 perf/k6/run.sh order.js -e FIRST_ID=<id> -e LAST_ID=<id> -e HOLD=3m
python -I perf/analysis/stage_report.py --target msa --start <시작 + setup 시간> --steps 5,10,20,40,80 --hold 180
perf/data/msa/check-concurrent.sh <FIRST_ID>          # concurrent.js 뒤
```

- 측정 뒤 되돌리기: **seed 전에** 시연 도구로 새 스냅샷을 만들고 측정 뒤 그걸로 복원 — `npm run demo -- save <이름>` → 측정 → `npm run demo -- restore <이름>` (브랜치 `gasic37200/perf-demo-data`의 `perf/demo/`). `restore base`는 base 저장 뒤 생긴 실제 회원까지 지운다 (11/22 사고, #661)
- 무너지는 단계에서 Gateway가 OOMKilled되면 SSH 터널도 끊길 수 있다 → 다음 측정 전에 `curl localhost:18080/actuator/health`
- 서비스가 재시작한 직후(차가운 JVM)에는 한계가 크게 낮게 나온다 → 1분 데우고 잰다
- 결과 원본: `k6/results/*-msa-*`, 장애 때 저장한 로그 `k6/results/msa-incidents/`

## Gateway 메모리 조정 뒤 재측정 (#661)

#628에서 OOMKilled가 난 둘러보기 · 섞어서만 같은 스크립트 · 단계 · 3분 유지로 다시 쟀다 (2026-11-27, 운영). 바꾼 것: Gateway 힙 70 → 45% · 다이렉트 메모리 64m, 서비스 연결 풀 상한 100 · 자리 대기 1초(초과 503) → PR #675. 원본 `k6/results/*-661-*`.

| 시나리오 · 단계 | #628 Gateway rps · p95 · 에러 | #661 Gateway rps · p95 · 에러 | #661 데이터 서버 CPU 최대 % |
|---|---|---|---|
| 둘러보기 40 | 40.0 · 33ms · 0% | 40.4 · 42ms · 0% | 34 |
| 둘러보기 80 | 86.2 · 144ms · 0% | 75.5 · 701ms · 0% | 80 |
| 둘러보기 160 | 에러 79% · **Gateway 2파드 OOMKilled** | 161.1 · 4,197ms · 31% (503) | 96 |
| 섞어서 10 | 11.7 · 362ms · 0% | 11.3 · 627ms · 0% | 64 |
| 섞어서 20 | 21.5 · 1,002ms · 0% | 19.7 · 201ms · 0% | 50 |
| 섞어서 40 | 34.0 · 9,970ms · 89.8% | 42.6 · 3,827ms · 0.75% | 95 |

- Gateway 재시작 0 · OOMKilled 0, 컨테이너 메모리 최대 446MiB (한도 512Mi)
- 둘러보기 160: product 쪽 연결 풀 100개가 차고 대기 최대 97 → Gateway가 1초 뒤 503 7,626건으로 끊음, 504 0. 붙잡힌 요청이 쌓이지 않아 힙 사용 최대 약 150MiB
- 한계는 그대로 둘러보기 80 · 섞어서 20~40. 병목은 데이터 서버 CPU와 product DB 연결 대기(160에서 대기 188)
- 둘러보기 80 p95가 144 → 701ms: product p95 자체가 557ms라 Gateway 원인은 아님. 원인 미확인 (seed 직후 DB 상태 추정)
- 섞어서 10 p95 627ms: 스냅샷 저장으로 서비스가 재시작한 지 약 25분, market p95 3.6초 · 앱 서버 CPU 100% (찬 JVM 영향으로 봄)
- 데이터: 측정 직전 `save pre661` → 측정 → `restore pre661`. 측정 전과 회원 · 상품 · 주문 수가 같음을 확인
- 조건 차이: 이번 측정 때 데이터 서버 Tempo가 메모리 한도 256MB에서 OOMKilled로 계속 재시작 중이었다(재시작 9천 회 넘음, 추적 저장 안 됨). #628은 Tempo가 데이터 서버 CPU 67~72%를 쓰던 때라, 데이터 서버 CPU · 낮은 단계 p95는 그대로 비교하지 않는다

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

측정 전. 아래 표를 `analysis/stage_timing.py` 출력으로 채운다 (가장 느린 단계는 스크립트가 표시).

| 대상 | 측정일 | 건수 | 전체 p50 · p95 | 가장 느린 단계 (p95) |
|---|---|---|---|---|
| 상품 키워드 검색 | | | | |
| 품종 의미 검색 | | | | |
| 챗봇 | | | | |

### 다시 재기

```bash
# 터널 (MSA 운영 측정과 같음)
ssh -N -L 19090:10.0.7.79:9090 replant-data          # 운영 Prometheus

cd perf/k6
# 1) 상품 키워드 검색 (로그인 없음)
k6 run -e TARGET=product -e RPM=120 -e DURATION=5m --summary-export results/stages-product-$(date +%Y%m%d-%H%M).json search-stages.js
# 2) 품종 의미 검색 (로그인 없음, 분당 9)
k6 run -e TARGET=species -e RPM=9 -e DURATION=6m --summary-export results/stages-species-$(date +%Y%m%d-%H%M).json search-stages.js
# 3) 챗봇 (perf-buyer-1..3 로그인, 비밀번호 ~/.replant-perf/password)
./run.sh chatbot.js -e MEMBERS=3

# 측정마다 시작 · 끝 시각으로 단계별 표
python3 -I ../analysis/stage_timing.py --start <시작> --end <끝> --only product
```

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
| 질문 임베딩 캐시 | 키 = 모델 · 차원 · 정리한 질문(앞뒤 공백 제거, 연속 공백 하나로)의 SHA-256, 값 = 벡터. ai 파드 메모리(Caffeine) 1,000개 · 24시간 (`custom.query-embedding-cache.*`). 품종 의미 검색 · 챗봇이 같이 씀. 챗봇 이어 묻기는 「직전 질문 + 이번 질문」 문장이 키 |
| 캐시 지표 | `ai_query_embedding_cache_total{result="hit"\|"miss"}`, `ai_query_embedding_cache_size`. `embedding` 단계 Timer는 적중 때도 남아(짧게) 전후 p50 · p95를 그대로 비교한다 |
| 챗봇 검색 동시 실행 | `product_search` · `species_search`를 가상 스레드 두 개에서 동시에. 두 검색을 묶은 시간은 새 단계 `search` (두 단계와 겹쳐 「가장 느린 단계」 비교에서는 뺌) |
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
| 챗봇 | `product_search` + `species_search` (개선 전은 차례로) · `search` (개선 뒤) | 22 + 59 · 54 + 95 | |
| 챗봇 | `total` | 1,476 · 4,438 | |
| 질문 임베딩 캐시 | 적중률 | - | |

- 품종 의미 검색 입력은 문장 10개를 분당 9번 6분(54건) 돌리므로, 배포 뒤 첫 바퀴 10건만 미적중이고 나머지는 적중한다. 「같은 질문을 다시 보냈을 때」의 시간이고, 실제 사용자 질문은 겹치는 비율이 이보다 낮다
- `embedding` Timer는 적중 · 미적중을 나누지 않는다. 미적중은 개선 전과 같은 OpenAI 호출이라, p50은 적중 시간, p95는 미적중이 5%보다 많으면 미적중 시간에 가깝게 나온다. 적중률을 함께 적는다
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
