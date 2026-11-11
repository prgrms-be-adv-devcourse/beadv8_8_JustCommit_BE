# 부하 측정 관측 도구 (#351)

내 PC에서 Prometheus · Grafana를 띄우고, 서버 지표는 SSH 터널로 가져온다. 서버 보안 그룹은 새로 열지 않는다.

```
[내 PC]  Prometheus :19090 ─┬─ :18081 ══ SSH 터널 ══▶ 서버 127.0.0.1:8081  앱 /actuator/prometheus
         Grafana    :13000  ├─ :19100 ══ SSH 터널 ══▶ 서버 127.0.0.1:9100  node-exporter
         k6 ── 결과 전송 ───┘  :19187 ══ SSH 터널 ══▶ 서버 127.0.0.1:9187  postgres-exporter
```

## 1. SSH 터널 (측정하는 동안 켜 둔다)

```bash
ssh -i <pem 경로> -N \
  -L 18081:127.0.0.1:8081 \
  -L 19100:127.0.0.1:9100 \
  -L 19187:127.0.0.1:9187 \
  <사용자>@<서버 주소>
```

서버 쪽은 실험 브랜치의 `docker-compose.prod.yml`(프로필 `prod,perf`, exporter 2개)로 떠 있어야 한다.

## 2. Prometheus · Grafana

```bash
docker compose -f perf/monitoring/docker-compose.yml up -d
```

| 화면 | 주소 |
|---|---|
| Grafana 대시보드 「replant 부하 측정」 | http://localhost:13000/d/replant-perf |
| Prometheus 수집 상태 (`app` · `node` · `postgres`가 UP인지) | http://localhost:19090/targets |

## 3. k6 결과도 같은 화면에

```bash
K6_PROMETHEUS_RW_SERVER_URL=http://localhost:19090/api/v1/write \
  k6 run --out experimental-prometheus-rw <스크립트>
```

대시보드 「TPS (전체 · k6가 보낸 양)」에서 k6가 보낸 양과 서버가 처리한 양을 비교한다. 둘이 벌어지면 서버가 밀리고 있다는 뜻이다.

## 대시보드에서 볼 것

| 패널 | 보는 이유 |
|---|---|
| TPS · p95 · p99 · 에러율 | 한계점 판정 (p95 2초 또는 에러 5% 초과) |
| Hikari 커넥션 · 기다리는 요청 | 가설 1 · 3: 커넥션 10개가 모자라는지 |
| Tomcat 스레드 | 커넥션 10개 대비 요청 스레드가 얼마나 쌓이는지 |
| DB 잠금 수 | 가설 1: 지갑 행 잠금 대기 |
| 서버 CPU의 `steal` | t3 크레딧이 바닥나 CPU가 묶이면 steal이 올라간다 → 그 측정은 무효 |
