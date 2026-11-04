# 환경변수

> 언제 읽나: 환경변수 · 비밀값을 추가하거나 찾을 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro A3

## 변수 표

값은 절대 적지 않습니다.

| 변수 | 쓰는 서비스 | 설명 |
|---|---|---|
| `DB_HOST` · `DB_PORT` · `DB_NAME` · `DB_USERNAME` · `DB_PASSWORD` | 모든 서비스 | 서비스마다 다른 DB |
| `KAFKA_BOOTSTRAP_SERVERS` · `SPRING_APPLICATION_NAME` | 모든 서비스 | – |
| `*_TOPIC` (9개) | 각 서비스 | 도메인 토픽 이름 (`common-messaging` 상수 기본값) |
| `JWT_PRIVATE_KEY` | member | RS256 개인키 (세미의 `JWT_SECRET` 대체) |
| `JWT_PUBLIC_KEY` | Gateway · 모든 서비스 | 이중 검증용 공개키 |
| `OAUTH2_STATE_SECRET` | member | 카카오 state 서명 (JWT 키에서 파생하지 않음) |
| `INTERNAL_API_KEY` | 모든 서비스 | `/internal/**` 확인 |
| `REDIS_HOST` · `REDIS_PORT` · `REDIS_PASSWORD` | member · market · Gateway | – |
| `KAKAO_CLIENT_ID` · `KAKAO_CLIENT_SECRET` | member | 카카오 로그인 |
| `MAIL_USERNAME` · `MAIL_PASSWORD` | member | Gmail SMTP |
| `FRONTEND_BASE_URL` · `COOKIE_SECURE` | member | 리다이렉트 · 쿠키 Secure |
| `ACCOUNT_ENCRYPTION_KEY` | member | 계좌 암호화 |
| `TOSS_SECRET_KEY` · `PAYMENT_KEY_ENCRYPTION_KEY` | payment | 토스페이먼츠 |
| `COURIER_BASE_URL` · `COURIER_API_KEY` · `WEBHOOK_API_KEY` | market | 모의 택배사 · 웹훅 |
| `AWS_REGION` · S3 버킷 | product | 사진 업로드 |
| `PLANT_ID_API_KEY` | product | 사진 품종 판별 |
| AWS 자격 증명 (Rekognition · S3) | product | ⬜ 키 대신 EC2 역할 사용 예정 → [AWS](../infra/aws.md) |
| 임베딩 API 키 | ai-service | 외부 임베딩 (⬜ 변수 이름 미정) |

## 비밀값 관리

- 비밀값은 GitHub Secrets에 두고 배포 때 서버 `.env`로 씁니다.
- 키 · pem · `.env`를 커밋하지 않습니다 (액세스 키 유출은 팀 AWS 지원 회수 사유).
- 로컬은 각자 `.env`(git 제외)를 씁니다.
