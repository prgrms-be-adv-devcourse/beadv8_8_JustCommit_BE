# Redis 키

> 언제 읽나: Redis 키를 새로 쓰거나 TTL을 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-17 · 관련: Miro A1, legacy/infrastructure-todo.md

## 규칙

- 키 형식 `{도메인}:{용도}:{id}` (소문자, 콜론 구분)
- **모든 키에 TTL** (`noeviction`이라 TTL 없는 키가 쌓이면 쓰기가 실패함)
- 이메일은 소문자로 바꾼 뒤 키에 넣음
- 토큰 원문은 저장하지 않고 SHA-256 해시를 씀: `Bearer `를 뺀 토큰 → UTF-8 → SHA-256 → 소문자 16진수 64글자. 쓰는 쪽과 읽는 쪽 모두 `common-security-core`의 `TokenHash.sha256Hex`를 씀

## 키 목록

| 키 | 값 | TTL | 쓰는 서비스 | 용도 |
|---|---|---|---|---|
| `auth:refresh:{토큰 해시}` | `{memberId}:{인증 버전}` | 1일 | member | Refresh 토큰. 재발급 때마다 교체 (꺼내며 삭제, 회원별 목록과 함께 Lua로 처리). 인증 버전이 회원과 다르면 재발급 거절, 형식이 맞지 않는 값(옛 형식 `memberId`)은 없음으로 봄 |
| `auth:email:{목적}:{email}` | `{요청 식별자}:{인증번호 6자리}:{묶음 정보}` | 5분 | member | 목적별 인증번호. 목적 부분은 `signup` · `password-reset` · `account-change`(API 값은 [외부 API](external-apis.md#이메일-인증-목적)). 발송 예약의 주인 요청만 저장하고, 새 번호 저장과 틀린 횟수 초기화를 Lua 한 번에 처리. 묶음 정보는 재설정 · 계좌 변경만 발송 당시 `{memberId}:{인증 버전}`(가입은 비어 있음)이고, 번호가 맞으면 번호를 지우는 같은 실행에서 돌려줌 |
| `auth:email:attempts:{목적}:{email}` | 틀린 횟수 | 5분 | member | 비교 · 증가 · 폐기를 Lua 한 번에. 5번째 오답에 인증번호와 함께 삭제 |
| `auth:email:verified:{목적}:{email}` | `true` | 30분 | member | 인증 완료 표시 (지금은 가입만 씀. 재설정은 표시 대신 재설정 토큰) |
| `auth:email:cooldown:{email}` | 요청 식별자 | 60초 | member | 같은 이메일의 발송 예약 1분 1회 (목적과 관계없이 인증 메일 · 가입 계정 안내가 함께 씀). 키가 있는 동안 막고, 남은 시간은 밀리초를 초로 올림(최소 1초) |
| `auth:email:daily:{용도}:{email}` | 요청 식별자 Set | 첫 예약부터 1일 | member | 원소 수 = 그 용도의 하루 발송 예약 횟수(최대 5). 용도 `signup` · `password-reset` · `account-change` · `account-reminder`(가입 계정 안내)별로 따로 세어 한 용도의 요청이 다른 용도의 한도를 소진하지 않음(60초 재발송 제한은 `cooldown` 키로 이메일 단위 공유). 메일을 보내지 않고 끝난 요청(대상 아님 등)도 포함. SMTP 실패 · 인증번호 저장 거절이면 자기 요청 식별자만 빼고 재발송 제한도 자기 것일 때만 지움 (하루 구간이 바뀌었으면 새 Set에 영향 없음, 마지막 원소가 빠지면 키도 사라짐). 새 버전은 옛 키 `auth:email:daily:{email}`의 예약 횟수를 이어받지 않음. 옛 키는 구버전이 더 이상 쓰지 않으면 남은 TTL에 따라 만료(구버전과 신버전이 함께 실행되는 동안에는 서로 다른 하루 키를 씀) |
| `auth:password-reset:{토큰 해시}` | `{memberId}:{발송 당시 인증 버전}` | 30분 | member | 비밀번호 재설정 토큰. 꺼내며 삭제(`GETDEL`, 한 번만). 재설정 때 회원 잠금 안에서 인증 버전을 다시 비교 |
| `auth:account-change:{토큰 해시}` | `{memberId}:{발송 당시 인증 버전}` | 10분 | member | 계좌 변경 토큰 (#413). 변경 전에 지우지 않고 조회 → 회원 잠금 안에서 인증 버전 비교 · 중복 사전 조회 뒤 꺼내며 삭제(`GETDEL`, 한 번만) |
| `auth:password:failures:{memberId}` | `{인증 버전}:{틀린 횟수}` | 첫 오답부터 1일 | member | 비밀번호 변경의 현재 비밀번호 오답. 같은 버전에서 5회면 변경 요청 차단. 같은 버전 오답은 만료 시각 유지(`KEEPTTL`), 다른 버전의 첫 오답부터 새 1일. 변경 · 재설정이 커밋되면 변경 전 버전의 값일 때만 삭제 |
| `auth:blacklist:{토큰 해시}` | `1` | 토큰 남은 수명 (최대 30분) | member 씀 · Gateway 읽음 | 로그아웃한 Access 토큰 거절 |
| `auth:revoked-member:{memberId}` | 끊은 시각 (초) | 보관 기한까지 (끊은 시각 + Access 수명 30분 + 여유 60초, 밀리초로 설정) | member 씀 · Gateway 읽음 | `iat <= 끊은 시각`인 토큰 모두 거절. 쓰기는 Lua: 더 이른 끊은 시각으로 덮어쓰지 않고, 같은 끊은 시각이면 긴 보관 기한 유지. 기록 실패는 재처리 스케줄러가 다시 씀 → [ADR 0022](../decisions/0022-token-revocation-and-retry.md) |
| `auth:refresh-set:{memberId}` | Refresh 토큰 해시 Set | 1일 (토큰을 저장할 때마다 갱신) | member | 탈퇴 · 비밀번호 변경 때 그 회원의 옛 인증 버전 Refresh 삭제를 한 번 시도 (폐기 직후 새로 발급된 Refresh는 남김, 삭제에 실패해도 버전 비교로 재발급 거절). 저장 · 삭제는 Refresh 키와 함께 Lua로 처리. 만료된 Refresh의 해시는 남을 수 있고, 새 토큰 발급이 계속되면 TTL도 갱신돼 오래된 해시가 장기간 누적될 수 있음 (정리 방식은 사용량을 보고 정함). Refresh 키와 이 Set을 한 Lua 스크립트에서 함께 다루며 Redis 한 대 구성을 전제로 함 — 현재 키 구조는 Cluster의 동일 슬롯을 보장하지 않으므로, Cluster 전환 시 키 구조와 조회 방식을 함께 재설계해야 함 |

배포 주의 (#412): 이메일 인증 키에 목적이 들어가 형식이 바뀌었습니다. 배포 전에 받은 가입 인증번호 · 인증 완료 표시는 새 버전에서 읽지 못하므로 다시 받아야 합니다 (최대 30분 동안 영향).

## 예외

Gateway의 SCG `RedisRateLimiter`는 기본 키 `request_rate_limiter.{경로 id.ip}.tokens` · `.timestamp`를 씁니다 (예: `request_rate_limiter.{member-login.10.0.0.1}.tokens`). 키 형식 규칙의 예외이고, TTL은 라이브러리가 자동으로 겁니다.

## 설정

- 앱 서버 Docker 1대 (`redis:8-alpine`, ElastiCache 안 씀)
- 비밀번호 필수. K3s 파드(Gateway)가 접속해야 해서 `127.0.0.1` 대신 **서버 내부 주소에 바인딩**하고, 보안 그룹으로 외부 접속을 막음
- `maxmemory 256MB` + `noeviction` → 꽉 차면 쓰기 실패 (메모리 90% 알림 대상)
- AOF 켜짐 → 재시작해도 Refresh 토큰 유지 (서버가 매일 꺼졌다 켜짐)
