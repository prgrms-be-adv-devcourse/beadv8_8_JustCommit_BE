# 서버 배치 · 배포

> 언제 읽나: 서버 구성이나 CI/CD를 바꿀 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-19 · 관련: Miro 15, 노션 기획안 「배포 · 운영 변경」

## 서버 배치

| 서버 | 올라가는 것 |
|---|---|
| 앱 서버 t3.large | Nginx · Redis · 404-express · compose 서비스 5개(member · product · payment · settlement · notification) · K3s 단일 노드(Gateway 파드 2개 · market 파드 2개 · ai-service HPA CPU 70% 1→2개) |
| 데이터 서버 t3.medium | PostgreSQL(인스턴스 1개 안에서 서비스별 DB · 계정 + pgvector) · Kafka(KRaft 단일) · OpenSearch(힙 512MB) · Loki · Tempo · Prometheus · Grafana |

Gateway는 K3s Service 이름으로 market에 보내고 K3s가 살아 있는 파드에 나눠 줍니다. 두 market 파드는 같은 market DB를 씁니다 → [ADR 0014](../decisions/0014-gateway-market-k3s-two-pods.md). 포트 → [컨테이너 · 포트](../reference/ports.md)

## 메모리

- 컨테이너마다 `mem_limit` + JVM `MaxRAMPercentage=70`, 두 서버 모두 스왑 2GB
- Gateway · market을 2개씩 띄우면 앱 서버 예상 메모리는 **약 7GB / 8GB**입니다. 넘치면 모니터링을 **데이터 서버로** 옮기는 쪽을 봅니다 (앱 서버가 더 빠듯함)
- 실측 (2026-11-18, 부하 없이 모두 띄운 상태): 앱 서버 **4.4GB / 7.6GB**, 스왑 사용 없음. 부하를 줄 때의 값은 성능 측정 때 다시 잽니다 (⬜)

| 무엇 | 메모리 (상한) |
|---|---|
| Gateway 파드 2개 | 163 · 186MiB (512Mi) |
| market 파드 2개 | 356 · 368MiB (768Mi) |
| ai 파드 1개 | 261MiB (640Mi) |
| member · product | 360 · 407MiB (768m) |
| payment · settlement · notification | 298 · 309 · 277MiB (512m) |
| Redis | 5MiB (320m) |
| K3s · Nginx · CD 러너 3개 등 나머지 | 약 1.4GB (서비스 띄우기 전 기준) |

## CI/CD

워크플로는 `.github/workflows/cd.yml`입니다. 404-express는 별도 레포 `gasic37200/404-express`의 `cd.yml`이 main push마다 같은 러너로 배포합니다 (서버 `~/404-express`).

1. develop 머지
2. 경로 필터로 바뀐 서비스만 고름 (`common/` · `Dockerfile` · Gradle 설정 · `cd.yml`이 바뀌면 전체). 테스트는 하지 않음 (PR CI에서)
3. 서비스별 Docker 이미지 빌드 → GHCR push
4. 앱 서버의 self-hosted 러너가 배포: compose 서비스는 `{서비스}_TAG=<커밋7자리>`로 `docker compose pull` · `up -d`, K3s(Gateway · market · ai)는 `kubectl set image` + `rollout status` — **롤링 배포**라 배포 중에도 끊기지 않음

배포 단계는 아래 규칙을 따릅니다.

| 항목 | 규칙 |
|---|---|
| 서버 접속 | 앱 서버에 설치한 self-hosted 러너(라벨 `replant-app`)가 서버 안에서 실행. SSH 22번을 GitHub에 열지 않고, AWS 키 · SSH 키를 GitHub에 두지 않음 |
| 켜고 끄기 | 레포 변수 `CD_DEPLOY`가 `true`일 때만 배포. 아니면 이미지만 올림 |
| 대상 | 이미 떠 있는 서비스만 새 이미지로 바꿈. 처음 올리기는 수동 (`deploy/k3s/README.md`, `docker-compose.app.yml` 머리말) |
| GHCR 로그인 | 실행마다 `GITHUB_TOKEN`으로 임시 폴더에 로그인 · 끝나면 지움 (서버 `~/.docker` 설정은 그대로) |
| 실패 | 빌드가 하나라도 실패하면 배포하지 않음. 롤아웃 실패 시 옛 파드가 계속 응답하고, 되돌리기는 `kubectl -n replant rollout undo` |
| 기동 대기 | compose 서비스는 `--wait`로 healthy까지 기다림. 헬스체크 `start_period` 120초 + 10초 × 3번 실패 = 최대 약 150초 (`--wait-timeout 180`), K3s는 `rollout status --timeout=5m` |
| 실패 로그 | 실패한 그때 Actions 로그에 남김: compose는 컨테이너 상태 · 헬스체크 기록 · `docker logs --tail 80`, K3s는 최근 파드 2개의 상태 · 이벤트 · 로그(`--previous` 포함). 공개 로그라 `docker inspect` · `kubectl describe` 전체는 찍지 않고 `password` · `secret` · `token` · `key` 값과 URL 안 비밀번호는 `***`로 가림 |
| 서버가 꺼진 시간 | 배포는 서버가 켜질 때까지 대기 (24시간 넘으면 실패) |
| 외부 PR | 레포가 공개라, 외부 기여자 PR의 워크플로는 승인 후 실행으로 설정 (러너가 있는 서버를 보호) |

이미지는 아래 규칙으로 만듭니다.

| 항목 | 규칙 |
|---|---|
| Dockerfile | 루트 `Dockerfile` 하나를 모든 서비스가 같이 씀. 빌드할 모듈 폴더를 `MODULE`로 넘김: `docker build --build-arg MODULE=services/member .` (Gateway는 `MODULE=gateway`) |
| 이미지 이름 | 서비스마다 저장소 하나: `ghcr.io/prgrms-be-adv-devcourse/replant-{서비스}` (`{서비스}`는 모듈 폴더 이름: `gateway` · `member` · `product` · `market` · `payment` · `settlement` · `notification` · `ai`) |
| 태그 | 커밋 짧은 해시 7자리 (예: `replant-member:a1b2c3d`) + develop 최신에 `latest`. 배포 · 되돌리기는 항상 커밋 태그로 함 |
| 컨테이너 안 | 포트 8080(`SERVER_PORT`), `app` 사용자, 시간대 `Asia/Seoul`. 테스트는 이미지 빌드에서 건너뜀(CI에서 실행) |

Flyway는 앱이 뜰 때 실행됩니다 → [DB · Flyway](database.md)

## 한계와 백업

- 서버 2대는 역할 분리일 뿐 이중화가 아닙니다. 파드를 여러 개 띄워도 같은 EC2 위라 **EC2가 꺼지면 함께 멈춥니다.** 파드 이중화는 프로세스 장애와 배포 공백만 막습니다.
- 대책: 자동 재시작, EBS 보존, 하루 1회 `pg_dump` → S3, 생존 알림
- DB 백업: 데이터 서버 cron이 매일 17:30(서버가 꺼지기 전)에 `deploy/data/backup-postgres.sh` 실행 → 서비스 DB마다 `pg_dump -Fc` → S3 `backups/postgres/{날짜}/` (7일 보관, [AWS](aws.md)). 복원 방법은 스크립트 머리말
- EC2는 09:00~18:00 자동 실행이라 매일 꺼졌다 켜집니다 (Redis AOF 필수)
- 다음 단계(포트폴리오): DB 복제 · Kafka 클러스터
