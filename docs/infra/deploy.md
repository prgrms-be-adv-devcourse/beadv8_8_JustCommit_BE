# 서버 배치 · 배포

> 언제 읽나: 서버 구성이나 CI/CD를 바꿀 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro 15, 노션 기획안 「배포 · 운영 변경」

## 서버 배치

| 서버 | 올라가는 것 |
|---|---|
| 앱 서버 t3.large | Nginx · Gateway · 서비스 6개 · Redis · 404-express · K3s 단일 노드(ai-service만, HPA CPU 70% 1→2개) |
| 데이터 서버 t3.medium | PostgreSQL(서비스별 DB + pgvector) · Kafka(KRaft 단일) · OpenSearch(힙 512MB) · Loki · Prometheus · Grafana |

두 서버는 사설 IP로 통신합니다. 포트 → [컨테이너 · 포트](../reference/ports.md)

## 메모리

- 컨테이너마다 `mem_limit` + JVM `MaxRAMPercentage=70`
- 두 서버 모두 스왑 2GB
- Sprint 1에 실측하고, 데이터 서버(medium)가 넘치면 모니터링을 앱 서버로 옮깁니다 (⬜)

## CI/CD

1. develop 머지
2. 경로 필터로 바뀐 서비스만 빌드 · 테스트 (`common/`이 바뀌면 전체)
3. 서비스별 Docker 이미지 → GHCR push
4. 서버에 SSH: `docker compose pull` · `up -d`, ai-service는 `kubectl set image`

Flyway는 앱이 뜰 때 실행됩니다 → [DB · Flyway](database.md). 테스트 단계 → [테스트 전략](../testing/strategy.md)

## 한계와 백업

- 서버 2대는 **역할 분리일 뿐 이중화가 아닙니다.** 어느 쪽이 멈춰도 서비스가 멈춥니다.
- 대책: 컨테이너 `restart: unless-stopped`, EBS 보존, 일 1회 `pg_dump` → S3, 생존 알림은 앱 서버 쪽
- EC2는 09:00~18:00 자동 실행이라 매일 꺼졌다 켜집니다 (Redis AOF 필수)
- 다음 단계(포트폴리오에 적을 것): DB 복제 · Kafka 클러스터
