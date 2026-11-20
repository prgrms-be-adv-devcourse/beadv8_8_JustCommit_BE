# 부하 측정 (#351)

세미 모놀리스(Spring Modulith)가 어디서 무너지는지 숫자로 남기고, 나중에 같은 스크립트로 MSA를 재서 비교하려고 만든 도구.
결과 · 해석은 노션 「replant 세미 프로젝트 측정 기록」(측정 기록 DB에 측정 한 번 = 한 행).

| 폴더 | 무엇 |
|---|---|
| `monitoring/` | 내 PC에서 띄우는 Prometheus · Grafana(대시보드 21패널), 서버 지표는 SSH 터널로 수집 |
| `fakes/` | 택배 · 토스 대신 쓰는 WireMock 매핑, 응답 지연을 바꾸는 `set-delay.sh` (가설 4) |
| `data/` | 테스트 데이터 SQL (회원 · 상품 · 주문 · 가설별 데이터), 측정 사이 정리 방법 |
| `k6/` | 부하 시나리오 (Smoke · 둘러보기 · 주문 · 섞어서 · 동시 구매 · 내 주문 목록), 실행 스크립트, 결과 원본 |
| `analysis/` | k6 실행 시각으로 Prometheus에서 단계별 표를 뽑는 `stage_report.py` |

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
