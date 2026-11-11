# k6 시나리오 (#351)

| 스크립트 | 무엇 | 부하 |
|---|---|---|
| `smoke.js` | 1명이 로그인 → 상품 목록 · 품종 · 검색 · 상세 → 장바구니 → 주문 → 주문 목록 → 판매자 정산 조회를 한 바퀴 | 없음 (스크립트 · 서버 · 데이터 확인용) |

## 실행

준비: `perf/monitoring` 띄우기 + SSH 터널 (`perf/monitoring/README.md`), 테스트 데이터 (`perf/data/README.md`)

```bash
perf/k6/run.sh smoke.js
```

- k6는 Docker(`grafana/k6:1.0.0`)로 돈다. 결과는 Prometheus로 보내져 Grafana 「replant 부하 측정」에 함께 나온다.
- 요약 원본은 `perf/k6/results/<스크립트>-<시각>.json`에 남는다.
- 대상 주소는 `BASE_URL`(기본 `https://replant.kro.kr`), 비밀번호는 `~/.replant-perf/password`.
- 요청 이름은 `GET /products/{id}`처럼 경로 모양으로 묶는다 (상품 id마다 지표가 흩어지지 않게).

## 알아 둘 응답 모양 (세미 코드 그대로)

- 로그인 · 상품은 `{status, message, data}`로 감싸서 오고, 장바구니 · 주문은 감싸지 않고 바로 온다.
