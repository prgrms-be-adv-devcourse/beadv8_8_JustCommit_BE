# 인증 · 인가 규칙

> 언제 읽나: 토큰 · 권한 · 공개 경로 · 로그아웃을 다룰 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-24 · 관련: Miro 6, A1, 노션 기획안 「인증 · 인가」

## 토큰 발급과 검증

| 항목 | 내용 |
|---|---|
| 서명 | RS256. 개인키는 member만 가짐 (`JWT_PRIVATE_KEY`), 공개키는 Gateway와 모든 서비스가 환경변수로 받음 (`JWT_PUBLIC_KEY`, JWKS 안 씀) |
| 수명 | Access 30분, Refresh 1일 (재발급 때 새로 시작) |
| 1차 검증 (Gateway) | 서명 · 만료 · 토큰 type + `auth:blacklist` · `auth:revoked-member` 확인 |
| 2차 검증 (각 서비스) | 공개키로 서명 · 만료만. 회원 정보는 토큰에서 꺼냄 (`AuthMember`) |
| 전달 | Gateway는 토큰을 그대로 넘김. `X-User-*` · `X-Member-*` 헤더는 쓰지 않음 |
| 키 교체 | 이번 범위 밖. 키를 바꾸면 모든 회원이 다시 로그인 (한계, 노션 2절 11/6 결정) |
| 왜 이중 검증인가 | 포트가 실수로 열려도 위조 토큰이 통과하지 않게 (심층 방어) → [ADR 0001](../decisions/0001-scg-rs256-double-validation.md) |

코드 위치: 검증 로직 · `AuthMember` → `common-security`, 서비스 필터 → `common-security`(서블릿), Gateway 필터 → `gateway/` 안 (`JwtAuthenticationFilter`, 쓰는 곳이 Gateway 하나뿐이라 공통 모듈로 빼지 않음), 발급 → member → [공통 모듈](../conventions/common-modules.md)

## 토큰 폐기

| 상황 | Redis 키 | 효과 | TTL |
|---|---|---|---|
| 로그아웃 | `auth:blacklist:{토큰 해시}` | 이 기기의 Access 토큰만 거절 (서명이 맞는 토큰만 저장) + 이 기기 Refresh 삭제 | 토큰 남은 수명 |
| 탈퇴 · 비밀번호 변경 · 재설정 · 소셜 연결 해제(#422) | `auth:revoked-member:{memberId}` = 끊은 시각(초) | `iat <= 끊은 시각`인 토큰 모두 거절 (같은 초에 발급된 토큰도 막음. 폐기 뒤의 새 Access는 발급 초가 최근 폐기 초보다 클 때만 발급 → 아래 「시각」) + 인증 버전 +1로 그 전 Refresh 재발급 거절 (옛 인증 버전의 Refresh 삭제를 한 번 시도하고, 삭제에 실패해도 버전 비교로 재발급 거절) | 보관 기한까지 (끊은 시각 + Access 수명 30분 + 여유 60초) |

여러 기기 동시 로그인은 허용합니다. 쓰는 쪽은 member, 읽는 쪽은 Gateway입니다. 키 상세 → [Redis 키](../reference/redis-keys.md)

### 탈퇴 · 비밀번호 변경 · 재설정의 발급 순서와 보장 범위

| 항목 | 내용 |
|---|---|
| 발급 순서 | 로그인 · 소셜 로그인 · 재발급 · 폐기가 같은 회원 행을 잠금 (대기 3초, 넘으면 `MEMBER_BUSY`: 로컬 로그인 · 재발급은 409, 카카오 로그인은 프론트 `/oauth/callback?error=MEMBER_BUSY`로 리다이렉트). 발급이 잠금을 먼저 획득하면 DB 시계가 역행하지 않는 한 `iat`가 폐기 시각 이하이고, 폐기가 먼저 잠그면 이후 발급은 변경된 회원 상태와 인증 버전을 확인함. 재발급은 회원 상태 · 인증 버전 · 발급 시각을 확인한 뒤에 Refresh를 꺼내며 삭제함 (이 세 확인에서 실패하면 Refresh를 소비하지 않음. 꺼낸 뒤 회원 · 인증 버전 최종 확인에서 거절된 요청은 이미 Refresh를 소비한 상태) |
| 시각 | `iat`와 끊은 시각 모두 잠금 · 검사 뒤 DB `clock_timestamp()` (초 단위 버림). Gateway 규칙은 `iat <= 끊은 시각` 그대로이고, 발급 쪽이 발급 초(DB 현재 시각의 초)가 그 회원의 최근 폐기 초(가장 늦은 끊은 시각의 초)보다 클 때만 발급함 (로컬 로그인 · 기존 회원의 소셜 로그인 · 재발급 공통, `AccessIssueTimeGuard`). 폐기 기록이 없으면 기다리지 않고 발급. 조건을 충족하지 못하면 롤백해 회원 행 잠금을 풀고 트랜잭션 밖에서 기다린 뒤(한 번에 최대 1초) 처음부터 다시 실행하며, 두 번째 시도에서도 충족하지 못하면 `MEMBER_BUSY` (응답 방식은 위 「발급 순서」와 같음). `iat`는 읽은 DB 시각 그대로 → [ADR 0032](../decisions/0032-access-issue-after-revocation-second.md) |
| 기록 | 회원 변경 · 인증 버전 증가 · 폐기 작업을 한 트랜잭션에 커밋한 뒤 Redis 기록. 기록을 확인하지 못한 작업은 재처리 스케줄러가 보관 기한까지 다시 기록 |
| 결과 | `COMPLETED`(Redis 기록 확인 또는 보관 기한 경과) · `PENDING`(커밋됨, 기록 미확인) · 롤백은 예외 · 커밋 결과 불명은 `RevocationOutcomeUnknownException` (자동으로 다시 실행하지 않음) |
| 보장 범위 | Gateway는 Redis에 폐기 시각이 기록된 뒤부터 해당 Access를 거절함. `PENDING` 동안에는 기존 Access가 통과할 수 있음 |

소셜 연결 해제(폐기 사유 `SOCIAL_UNLINKED`)도 같은 폐기 · 발급 순서 · 보장 범위를 따릅니다.

결정 배경 → [ADR 0010](../decisions/0010-token-revocation-keys.md) · [ADR 0022](../decisions/0022-token-revocation-and-retry.md) · [ADR 0032](../decisions/0032-access-issue-after-revocation-second.md)

### 소셜 연결 해제 회원 (`INACTIVE`)

| 항목 | 내용 |
|---|---|
| 기존 토큰 | 카카오 연결 해제 웹훅의 비동기 처리에서 `SOCIAL_UNLINKED`로 폐기. 인증 버전이 올라가 그 전 Refresh 재발급은 거절되고, Redis에 폐기 시각이 기록된 뒤부터 기존 Access도 거절 (`PENDING` 동안 통과할 수 있는 한계는 같음) |
| 재발급 | 회원 상태가 `INACTIVE`면 401 `INVALID_REFRESH_TOKEN` (탈퇴 회원과 같은 응답) |
| 카카오 로그인 | 정상 카카오 로그인이면 회원 행 잠금 안에서 이전 미처리 수신 기록을 **항상**(`ACTIVE`여도) 무효로 하고, `INACTIVE`면 `ACTIVE`로 복구한 뒤 발급. `UNLINK_FROM_ADMIN`으로 `INACTIVE`가 된 회원도 복구(이용 정지로 취급하지 않음). 무효화 범위 → [탈퇴 흐름](../flows/signup-and-replica.md#재로그인). 발급 시각 확인을 무효화 · 복구보다 먼저 하며, 폐기와 같은 초에 재연결해도 발급 초가 최근 폐기 초보다 클 때만 발급함 (위 「시각」) |

회원 기능 제한 · 내부 계좌 조회 유지 → [member 정책](../services/member.md#정책), 처리 흐름 → [탈퇴 흐름](../flows/signup-and-replica.md#카카오-연결-해제-웹훅)

## 권한

| 권한 | 할 수 있는 일 |
|---|---|
| 비회원 | 상품 · 품종 조회 |
| `MEMBER` | 구매 (가입하면 항상 MEMBER) |
| `SELLER` | 판매 + 구매 |
| `ADMIN` | 관리자 (사진 확인 · 정산 관리 등) |

- 권한은 토큰의 `role` claim에 담고, ADMIN 경로는 Gateway에서 권한을 확인합니다.
- 판매자로 등록하면 재로그인 없이 반영됩니다. 토큰을 재발급할 때 DB에서 권한을 다시 조회합니다.

## 공개 경로

토큰 없이 통과하는 경로(상품 · 품종 조회, 로그인 · 로그아웃 · 재발급 · 가입 · 이메일 인증, 카카오 로그인, 택배 웹훅, 카카오 연결 해제 웹훅, 판매자 공개 정보)는 **Gateway와 각 서비스 양쪽에** 설정합니다. 한쪽만 바꾸면 401이 나거나 검증 없이 통과합니다. 택배 웹훅은 토큰 대신 `X-Api-Key`로, 카카오 연결 해제 웹훅은 `Authorization: KakaoAK {어드민 키}`로 확인합니다.

로그아웃(`POST /api/v1/auth/logout`)은 Gateway가 토큰 검사를 하지 않습니다. Access 토큰이 만료돼도 재발급 없이 로그아웃할 수 있게 하기 위해서입니다. member가 Refresh 쿠키로 이 기기의 Refresh를 지우고, 함께 온 Access 토큰이 서명이 맞고 아직 유효하면 블랙리스트에 넣습니다 (사용자 결정 2026-11-09). Access 토큰은 `Authorization: Bearer` 헤더로 받으며(쿠키 아님) 두 토큰 모두 선택입니다. 토큰이 없거나 유효하지 않으면 블랙리스트만 건너뛰고 200이며, Redis 처리 실패는 500입니다 (일부만 처리될 수 있어 클라이언트는 실패로 보고 재시도).

## 내부 API 보호

`/internal/**`은 회원 토큰 검사를 하지 않는 대신 세 겹으로 막습니다: Gateway 라우팅 제외 · 서비스 포트 외부 미개방 · `X-Internal-Key` 헤더 확인(`INTERNAL_API_KEY`, `common-security` 필터). 상세 → [서비스 간 REST 규칙](../conventions/internal-api.md)
