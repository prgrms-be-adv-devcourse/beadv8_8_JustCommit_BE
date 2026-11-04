# 컨테이너 · 포트

> 언제 읽나: 포트 번호나 외부 접근 범위를 확인할 때
> 상태: 🟨 제안 · 마지막 수정 2026-11-04 · 관련: Miro A2

## 포트 표

포트 A안: Docker 안에서는 서비스가 모두 8080, 로컬 실행 때만 서비스별 포트를 씁니다.

| 컨테이너 | 서버 | 포트 (운영) | 포트 (로컬) | 외부 접근 |
|---|---|---|---|---|
| nginx | 앱 (t3.large) | 80 / 443 | – | ✅ 인터넷 |
| gateway | 앱 | 8080 | 8080 | Nginx만 |
| member · product · market · payment · settlement · notification | 앱 | 각 8080 | 8081 ~ 8086 | ❌ |
| ai-service (K3s) | 앱 | ⬜ NodePort | 8087 | ❌ |
| redis | 앱 | 6379 | 6379 | ❌ |
| 404-express | 앱 | 기존 | – | Nginx 경유 |
| postgres (pgvector) | 데이터 (t3.medium) | 5432 | 5432 | 앱 서버 사설 IP만 |
| kafka (KRaft) | 데이터 | 9092 | 9092 | 앱 서버 사설 IP만 |
| opensearch | 데이터 | 9200 | 9200 | 앱 서버 사설 IP만 |
| loki · prometheus · grafana | 데이터 | 3100 · 9090 · 3000 | – | Grafana만 (⬜ 접속 제한 방법) |

## 보안 그룹

- 데이터 서버는 앱 서버 사설 IP에서만 받습니다.
- `/internal/**`은 Nginx · Gateway 라우팅에서 제외합니다.
- Nginx는 `X-Forwarded-For` · `X-Real-IP`를 `$remote_addr`로 덮어써 전달합니다 (IP 기준 요청 수 제한을 헤더 위조로 우회하지 못하게).
