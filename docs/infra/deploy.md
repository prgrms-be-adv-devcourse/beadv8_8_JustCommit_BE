# 서버 배치 · 배포

> 언제 읽나: 서버 구성이나 CI/CD를 바꿀 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-10 · 관련: Miro 15, 노션 기획안 「배포 · 운영 변경」

## 서버 배치

| 서버 | 올라가는 것 |
|---|---|
| 앱 서버 t3.large | Nginx · Redis · 404-express · compose 서비스 5개(member · product · payment · settlement · notification) · K3s 단일 노드(Gateway 파드 2개 · market 파드 2개 · ai-service HPA CPU 70% 1→2개) |
| 데이터 서버 t3.medium | PostgreSQL(인스턴스 1개 안에서 서비스별 DB · 계정 + pgvector) · Kafka(KRaft 단일) · OpenSearch(힙 512MB) · Loki · Tempo · Prometheus · Grafana |

Gateway는 K3s Service 이름으로 market에 보내고 K3s가 살아 있는 파드에 나눠 줍니다. 두 market 파드는 같은 market DB를 씁니다 → [ADR 0014](../decisions/0014-gateway-market-k3s-two-pods.md). 포트 → [컨테이너 · 포트](../reference/ports.md)

## 메모리

- 컨테이너마다 `mem_limit` + JVM `MaxRAMPercentage=70`, 두 서버 모두 스왑 2GB
- Gateway · market을 2개씩 띄우면 앱 서버 예상 메모리는 **약 7GB / 8GB**입니다. 넘치면 모니터링을 **데이터 서버로** 옮기는 쪽을 봅니다 (앱 서버가 더 빠듯함)
- Sprint 1에 실측합니다 (⬜)

## CI/CD

1. develop 머지
2. 경로 필터로 바뀐 서비스만 빌드 · 테스트 (`common/`이 바뀌면 전체)
3. 서비스별 Docker 이미지 → GHCR push
4. compose 서비스는 SSH로 `docker compose pull` · `up -d`, K3s(Gateway · market · ai)는 `kubectl set image` — **롤링 배포**라 배포 중에도 끊기지 않음

Flyway는 앱이 뜰 때 실행됩니다 → [DB · Flyway](database.md)

## 한계와 백업

- 서버 2대는 역할 분리일 뿐 이중화가 아닙니다. 파드를 여러 개 띄워도 같은 EC2 위라 **EC2가 꺼지면 함께 멈춥니다.** 파드 이중화는 프로세스 장애와 배포 공백만 막습니다.
- 대책: 자동 재시작, EBS 보존, 하루 1회 `pg_dump` → S3, 생존 알림
- EC2는 09:00~18:00 자동 실행이라 매일 꺼졌다 켜집니다 (Redis AOF 필수)
- 다음 단계(포트폴리오): DB 복제 · Kafka 클러스터
