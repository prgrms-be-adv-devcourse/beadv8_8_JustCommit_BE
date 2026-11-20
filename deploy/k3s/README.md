# K3s 매니페스트 (앱 서버)

앱 서버 K3s에 올리는 Gateway · market · ai 설정입니다. Gateway · market을 파드 2개로 두는 이유는 [ADR 0014](../../docs/decisions/0014-gateway-market-k3s-two-pods.md), ai의 자동 늘리기(HPA)는 [서버 배치 · 배포](../../docs/infra/deploy.md), 포트는 [컨테이너 · 포트](../../docs/reference/ports.md)를 봅니다.

| 파일 | 내용 |
|---|---|
| `namespace.yaml` | `replant` 네임스페이스 |
| `endpoints.yaml` | 서비스 주소 ConfigMap `replant-endpoints` (비밀값 아님) |
| `gateway.yaml` | Gateway 파드 2개 + NodePort 30080 |
| `market.yaml` | market 파드 2개 + NodePort 30083 |
| `ai.yaml` | ai 파드 1~2개 (HPA CPU 70%), 바깥 포트 없음 (Service `ai:8080`) |

## 서버에만 두는 것 (레포에 올리지 않음)

| Secret | 만드는 원본 | 들어갈 변수 이름 |
|---|---|---|
| `gateway-env` | `~/secrets/gateway.env` | `JWT_PUBLIC_KEY` · `REDIS_PASSWORD` · `CORS_ALLOWED_ORIGINS` |
| `market-env` | `~/secrets/market.env` | `DB_URL` · `DB_USERNAME` · `DB_PASSWORD` · `KAFKA_BOOTSTRAP_SERVERS` · `JWT_PUBLIC_KEY` · `INTERNAL_API_KEY` · `MEMBER_ADDRESS_EVENT_KEY` · `COURIER_BASE_URL` · `COURIER_API_KEY` · `WEBHOOK_API_KEY` |
| `ai-env` | `~/secrets/ai.env` | `DB_URL` · `DB_USERNAME` · `DB_PASSWORD` · `KAFKA_BOOTSTRAP_SERVERS` · `JWT_PUBLIC_KEY` · `INTERNAL_API_KEY` · `OPENAI_API_KEY` · `ANTHROPIC_API_KEY` |
| `ghcr-pull` | GHCR `read:packages` 토큰 | – |

- env 파일은 한 줄에 `이름=값` 하나. `JWT_PUBLIC_KEY`는 PEM 머리말을 뺀 한 줄 Base64로 넣습니다.
- 변수 설명은 [환경변수](../../docs/reference/env-vars.md)에 있습니다.

## 처음 올리기 (서버에서)

```bash
kubectl apply -f namespace.yaml
bash ~/make-env.sh   # deploy/scripts/make-env.sh — ~/secrets/*.env 작성 + gateway · market · ai Secret 갱신
kubectl -n replant create secret docker-registry ghcr-pull --docker-server=ghcr.io --docker-username=<GitHub ID> --docker-password=<토큰>
kubectl apply -f endpoints.yaml -f gateway.yaml -f market.yaml -f ai.yaml
kubectl -n replant get pods
```

`ghcr-pull`은 토큰이 셸 기록에 남지 않게 명령 앞에 공백을 하나 두고 실행합니다.

## 자주 쓰는 명령

| 할 일 | 명령 |
|---|---|
| 파드 상태 | `kubectl -n replant get pods -o wide` |
| 로그 | `kubectl -n replant logs deploy/market --tail=100` |
| 새 이미지로 배포 (롤링) | `kubectl -n replant set image deploy/market market=ghcr.io/prgrms-be-adv-devcourse/replant-market:<커밋7자리>` |
| 배포 진행 보기 | `kubectl -n replant rollout status deploy/market` |
| ai 개수 · CPU 보기 | `kubectl -n replant get hpa ai` |
| 되돌리기 | `kubectl -n replant rollout undo deploy/market` |
| Secret 값 바꾼 뒤 반영 | `bash ~/make-env.sh` 뒤 `kubectl -n replant rollout restart deploy/market` |
