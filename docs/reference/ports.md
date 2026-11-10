# 컨테이너 · 포트

> 언제 읽나: 포트 번호나 외부 접근 범위를 확인할 때
> 상태: 🟨 제안 · 마지막 수정 2026-11-10 · 관련: Miro A2, 노션 「정책 정리」 10.4 · 10.10

## 포트 표

Docker · K3s 안에서는 서비스가 모두 8080, 로컬 실행 때만 서비스별 포트를 씁니다.

| 컨테이너 | 서버 | 포트 (운영) | 포트 (로컬) | 외부 접근 |
|---|---|---|---|---|
| nginx | 앱 (t3.large) | 80 / 443 | – | ✅ 인터넷 |
| gateway (K3s, 파드 2개) | 앱 | NodePort 30080 (Nginx → Gateway) | 8080 | Nginx만 |
| market (K3s, 파드 2개) | 앱 | 8080 (Gateway → K3s Service 이름) · NodePort 30083 (compose 서비스 → market 내부 API) | 8083 | ❌ |
| member · product · payment · settlement · notification (compose) | 앱 | 각 8080 | 8081 · 8082 · 8084 ~ 8086 | ❌ |
| ai-service (K3s) | 앱 | NodePort 없음 (market만 부르므로 K3s 안에서만) | 8087 | ❌ |
| redis | 앱 | 6379 (서버 내부 주소) | 6379 | ❌ |
| 404-express | 앱 | 기존 | – | ❌ (내부망) |
| postgres (pgvector) | 데이터 (t3.medium) | 5432 | 5432 | 앱 서버 사설 IP만 |
| kafka (KRaft) | 데이터 | 9092 | 9092 | 앱 서버 사설 IP만 |
| opensearch | 데이터 | 9200 | 9200 | 앱 서버 사설 IP만 |
| loki · prometheus · grafana | 데이터 | 3100 · 9090 · 3000 | – | ❌ (Grafana는 SSH 터널로만) |
| tempo | 데이터 | 3200 (조회) · 4317 (OTLP 받기) | – | ❌ (Alloy · Grafana만) |

## 보안 그룹

- 외부 공개 포트는 80 · 443(Nginx)만. SSH 22는 팀원 IP만 엽니다.
- 데이터 서버는 앱 서버 사설 IP에서만 받습니다.
- `/internal/**`은 Nginx · Gateway 라우팅에서 제외합니다.
- Nginx는 `X-Forwarded-For` · `X-Real-IP`를 `$remote_addr`로 덮어써 전달합니다 (IP 기준 요청 수 제한 우회 방지).
