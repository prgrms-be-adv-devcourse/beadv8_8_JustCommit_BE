# Redis 키

> 언제 읽나: Redis 키를 새로 쓰거나 TTL을 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro A1, legacy/infrastructure-todo.md

## 규칙

- 키 형식 `{도메인}:{용도}:{id}` (소문자, 콜론 구분)
- **모든 키에 TTL** (`noeviction`이라 TTL 없는 키가 쌓이면 쓰기가 실패함)
- 이메일은 소문자로 바꾼 뒤 키에 넣음
- 토큰 원문은 저장하지 않고 SHA-256 해시를 씀

## 키 목록

| 키 | 값 | TTL | 쓰는 서비스 | 용도 |
|---|---|---|---|---|
| `auth:refresh:{토큰 해시}` | memberId | 1일 | member | Refresh 토큰. 재발급 때 `GETDEL`로 한 번만 사용 |
| `auth:email:{email}` | 인증번호 | 5분 | member | 이메일 인증번호 |
| `auth:email:cooldown:{email}` | `1` (SET NX) | 60초 | member | 재발송 막기 |
| `auth:email:verified:{email}` | `true` | 30분 | member | 인증 완료 표시 (가입 때 확인) |
| `auth:blacklist:{토큰 해시}` | `1` | 토큰 남은 수명 (최대 30분) | member 씀 · Gateway 읽음 | 로그아웃한 Access 토큰 거절 |
| `auth:revoked-member:{memberId}` | 끊은 시각 (초) | 30분 | member 씀 · Gateway 읽음 | 탈퇴 · 비밀번호 변경 시 그 전에 발급된 토큰 모두 거절 |
| ⬜ 회원별 Refresh 목록 (이름 미정) | Refresh 해시 Set | 1일 | member | 전체 로그아웃 때 Refresh 일괄 삭제 |
| `order:payment:ttl:{orderId}` | `1` | 남은 결제 시간 (최대 10분) | market | 결제 대기 주문 |

## 예외

Gateway의 SCG `RedisRateLimiter`는 기본 키 `request_rate_limiter.{ip}.tokens` · `.timestamp`를 씁니다. 키 형식 규칙의 예외이고, TTL은 라이브러리가 자동으로 겁니다.

## 설정

- 앱 서버 Docker 1대 (`redis:8-alpine`, ElastiCache 안 씀)
- 비밀번호 필수, 포트는 외부에 열지 않음 (Docker 네트워크만)
- `maxmemory 256MB` + `noeviction` → 꽉 차면 쓰기 실패 (메모리 90% 알림 대상)
- AOF 켜짐 → 재시작해도 Refresh 토큰 유지 (서버가 매일 꺼졌다 켜짐)
