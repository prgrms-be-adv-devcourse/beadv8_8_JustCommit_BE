# k6 시나리오 (#351)

| 스크립트 | 무엇 | 부하 |
|---|---|---|
| `browse.js` | 로그인 없이 목록 40% · 상세 30% · 검색 20% · 품종 10%, 초당 요청 수를 단계별로 올림 (`-e STEPS=10,20,40 -e HOLD=2m`) | 한계점 측정 |
| `smoke.js` | 1명이 로그인 → 상품 목록 · 품종 · 검색 · 상세 → 장바구니 → 주문 → 주문 목록 → 판매자 정산 조회를 한 바퀴 | 없음 (스크립트 · 서버 · 데이터 확인용) |
| `order.js` | 미리 로그인한 구매자가 장바구니 담기 → 주문(지갑 결제), 반복마다 새 상품 (`-e FIRST_ID= -e LAST_ID=`, `perf/data/more-products.sql` 출력) | 주문 한계점 (기본 5→10→20→40→80건/초) |
| `mixed.js` | `browse.js` 90% + `order.js` 10%를 동시에 (`-e ORDER_SHARE=0.1`) | 섞어서 한계점, Soak(`-e STEPS=10 -e HOLD=30m`) |
| `concurrent.js` | 같은 상품 하나를 50명이 동시에 주문 × 10라운드, 끝나면 `perf/data/check-concurrent.sql`로 DB 확인 | 정합성 (1명만 결제되는가) |
| `mypage.js` | 로그인한 구매자 · 판매자가 내 주문 · 판매 주문 · 정산 목록 첫 페이지 | 인덱스 비교 (`bulk-orders.sql` → `add-indexes.sql` 전후) |

결과 해석: 단계별 표는 `python -I perf/analysis/stage_report.py --start <run.sh가 찍은 시작 시각> --steps 10,20,40 --hold 120 --ramp 10` (Prometheus에서 단계마다 서버 처리량 · p95 · CPU · Hikari · 힙 등을 뽑는다). setup(미리 로그인)이 있는 스크립트는 시작 시각에 setup 시간(약 20초)을 더한다.

## 실행

준비: `perf/monitoring` 띄우기 + SSH 터널 (`perf/monitoring/README.md`), 테스트 데이터 (`perf/data/README.md`)

```bash
perf/k6/run.sh smoke.js
```

- k6는 내 PC에 직접 설치해서 돌린다 (Windows: `winget install GrafanaLabs.k6`, 측정 기록은 v2.3.0). 결과는 Prometheus로 보내져 Grafana 「replant 부하 측정」에 함께 나온다.
- 요약 원본은 `perf/k6/results/<스크립트>-<시각>.json`에 남는다.
- 대상 주소는 `BASE_URL`(기본 `https://replant.kro.kr`), 비밀번호는 `~/.replant-perf/password`.
- 요청 이름은 `GET /products/{id}`처럼 경로 모양으로 묶는다 (상품 id마다 지표가 흩어지지 않게).

## Docker로 돌리지 않는 이유

처음엔 Docker(`grafana/k6:1.0.0`)로 돌렸다. 초당 5 · 10개 시험에서 서버 안 p95는 37 ~ 85ms인데 k6 p95는 0.8 ~ 2.2초였다 (`waiting` p95 800ms). 같은 시각 PC에서 curl로 직접 보낸 요청은 첫 바이트 p95 115ms → Windows Docker Desktop 안의 k6가 지연을 만든 것이다. 부하 도구도 측정 대상이라, 서버 지표와 k6 지표가 크게 벌어지면 도구부터 의심한다.

## 알아 둘 응답 모양 (세미 코드 그대로)

- 로그인 · 상품은 `{status, message, data}`로 감싸서 오고, 장바구니 · 주문은 감싸지 않고 바로 온다.
