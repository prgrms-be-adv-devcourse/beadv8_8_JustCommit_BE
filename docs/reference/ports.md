# 컨테이너 · 포트

> 언제 읽나: 포트 번호나 외부 접근 범위를 확인할 때
> 상태: 🟨 제안 · 마지막 수정 2026-11-21 · 관련: Miro A2, 노션 「정책 정리」 10.4 · 10.10

## 포트 표

Docker · K3s 안에서는 서비스가 모두 8080, 로컬 실행 때만 서비스별 포트를 씁니다.

| 컨테이너 | 서버 | 포트 (운영) | 포트 (로컬) | 외부 접근 |
|---|---|---|---|---|
| nginx | 앱 (t3.large) | 80 / 443 | – | ✅ 인터넷 |
| gateway (K3s, 파드 2개) | 앱 | NodePort 30080 (Nginx → Gateway) | 8080 | Nginx만 |
| market (K3s, 파드 2개) | 앱 | 8080 (Gateway → K3s Service 이름) · NodePort 30083 (compose 서비스 → market 내부 API) | 8083 | ❌ |
| member · product · payment · settlement · notification (compose) | 앱 | 각 8080 → 서버 포트 8081 · 8082 · 8084 ~ 8086 (사설 IP `10.0.7.76`에만 바인딩, Gateway · market 파드가 이 주소로 부름) | 8081 · 8082 · 8084 ~ 8086 | ❌ |
| ai-service (K3s) | 앱 | NodePort 없음 (market만 부르므로 K3s 안에서만) | 8087 | ❌ |
| redis | 앱 | 6379 (서버 내부 주소) | 6379 | ❌ |
| 404-express (compose) | 앱 | 3000 (사설 IP `10.0.7.76`에만 바인딩, market 파드가 `COURIER_BASE_URL`로 부름). 웹훅은 Gateway NodePort 30080으로 보냄 | – | ❌ |
| 503-bank (compose, 모의 은행 서버) | 앱 | 3001 (사설 IP `10.0.7.76`, payment가 `BANK_BASE_URL`로 부름). 서버 `~/503-bank`, 볼륨 `503-bank_bank-data`는 지우지 않음(지급 기록) | – | ❌ |
| postgres (pgvector) | 데이터 (t3.medium) | 5432 | 5432 | 앱 서버 보안 그룹만 |
| kafka (KRaft) | 데이터 | 9092 | 9092 | 앱 서버 보안 그룹만 |
| opensearch | 데이터 | 9200 | 9200 | 앱 서버 보안 그룹만 |
| loki · prometheus | 데이터 | 3100 · 9090 | – | 앱 서버 보안 그룹만 (Alloy가 로그 · 지표를 밀어 넣음) |
| grafana | 데이터 | 3000 (`127.0.0.1`에만) | – | ❌ (SSH 터널로만) |
| tempo | 데이터 | 3200 (조회, compose 안에서 Grafana만) · 4317 (OTLP 받기) | – | 앱 서버 보안 그룹만 4317 (Alloy) |
| node-exporter | 데이터 (앱 서버는 Alloy 내장 기능으로 대신) | 9100 | – | ❌ (Prometheus만) |
| alloy | 앱 | 12345 (`127.0.0.1`에만, 상태 화면) · 4318 (`10.0.7.76`에만, 서비스 · 파드가 추적을 보냄) | – | ❌ |
| postgres-exporter | 데이터 | 9187 | – | ❌ (Prometheus만) |
| kafka-exporter | 데이터 | 9308 | – | ❌ (Prometheus만) |

## 보안 그룹

- 외부 공개 포트는 80 · 443(Nginx)만. SSH 22는 팀원 IP만 엽니다.
- 데이터 서버(`team06-replant-data-sg`)는 앱 서버 보안 그룹(`team06-replant-app-sg`)에서 오는 5432 · 9092 · 9200 · 3100 · 9090 · 4317만 받습니다. 3100 · 9090 · 4317은 앱 서버 Alloy가 로그 · 지표 · 추적을 보내는 곳입니다. IP 대신 보안 그룹을 참조해 앱 서버 IP가 바뀌어도 규칙은 그대로입니다. 외부 공개 포트는 없습니다.
- 서버 안에서도 컨테이너 포트는 그 서버의 사설 IP에만 엽니다 (`docker-compose.app.yml` · `docker-compose.data.yml`).
- `/internal/**`은 Nginx · Gateway 라우팅에서 제외합니다.
- Nginx는 `X-Forwarded-For` · `X-Real-IP`를 `$remote_addr`로 덮어써 전달합니다 (IP 기준 요청 수 제한 우회 방지).
