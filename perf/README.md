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
