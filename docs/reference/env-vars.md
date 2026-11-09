# 환경변수

> 언제 읽나: 환경변수 · 비밀값을 추가하거나 찾을 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-08 · 관련: Miro A3

## 변수 표

값은 절대 적지 않습니다.

| 변수 | 쓰는 서비스 | 설명 |
|---|---|---|
| `DB_HOST` · `DB_PORT` · `DB_NAME` · `DB_USERNAME` · `DB_PASSWORD` | 모든 서비스 | 서비스마다 다른 DB · 계정 |
| `KAFKA_BOOTSTRAP_SERVERS` · `SPRING_APPLICATION_NAME` | 모든 서비스 | – |
| `*_TOPIC` (13개) | 각 서비스 | 도메인 토픽 이름 (`common-messaging` 상수 기본값) |
| `JWT_PRIVATE_KEY` | member | RS256 개인키 |
| `JWT_PUBLIC_KEY` | Gateway · 모든 서비스 | 이중 검증용 공개키 |
| `OAUTH2_STATE_SECRET` | member | 카카오 state 서명 |
| `INTERNAL_API_KEY` | 모든 서비스 | `/internal/**` 확인 |
| 🟨 `MEMBER_ADDRESS_EVENT_KEY` | member · market | 주소 이벤트 전송 키 (판매자 주소 암호화) |
| `REDIS_HOST` · `REDIS_PORT` · `REDIS_PASSWORD` | member · market · Gateway | – |
| `KAKAO_CLIENT_ID` · `KAKAO_CLIENT_SECRET` | member | 카카오 로그인 · 연결 해제 |
| `MAIL_USERNAME` · `MAIL_PASSWORD` | member | Gmail SMTP |
| `FRONTEND_BASE_URL` · `COOKIE_SECURE` | member | 리다이렉트 · 쿠키 Secure |
| `ACCOUNT_ENCRYPTION_KEY` · ⬜ 계좌 HMAC 키 | member | 계좌 암호화 · 중복 확인. 탈퇴 이메일 · 휴대폰 HMAC에도 사용 |
| `TOSS_SECRET_KEY` · `PAYMENT_KEY_ENCRYPTION_KEY` | payment | 토스페이먼츠 |
| `COURIER_BASE_URL` · `COURIER_API_KEY` · `WEBHOOK_API_KEY` | market | 모의 택배사 · 웹훅 |
| `AWS_REGION` · S3 버킷 | product | 사진 업로드 (자격 증명은 EC2 IAM 역할) |
| ⬜ OpenAI API 키 | product | Moderation |
| ⬜ Anthropic API 키 | product | Claude Haiku 식물 식별 |
| 임베딩 API 키 | ai-service | 외부 임베딩 (⬜ 변수 이름) |

## 비밀값 관리

- 비밀값 원본은 서버 `~/secrets/`에 두고, 레포에는 `.env.example`(이름 · 설명)만 둡니다.
- 새 필수 환경변수는 `.env.example`에도 추가합니다 (배포 때 누락 검사).
- DB 계정 · 암호화 키(계좌 암호화 · HMAC · 결제 키)는 한 번 정하면 바꾸지 않습니다.
- 앱은 AWS 키 없이 EC2 IAM 역할을 씁니다. 키 · `.pem`은 레포 · 채팅 · 문서에 남기지 않습니다 (유출 2OUT).
