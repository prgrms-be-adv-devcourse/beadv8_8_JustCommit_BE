# 인증 · 인가 규칙

> 언제 읽나: 토큰 · 권한 · 공개 경로 · 로그아웃을 다룰 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-13 · 관련: Miro 6, A1, 노션 기획안 「인증 · 인가」

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
| 탈퇴 · 비밀번호 변경 | `auth:revoked-member:{memberId}` = 끊은 시각(초) | `iat <= 끊은 시각`인 토큰 모두 거절 (같은 초에 발급된 옛 토큰도 막음) + 그 회원 Refresh 모두 삭제 | 30분 (Access 수명) |

여러 기기 동시 로그인은 허용합니다. 쓰는 쪽은 member, 읽는 쪽은 Gateway입니다. 회원의 Refresh는 회원별 목록(`auth:refresh-set:{memberId}`)으로 한 번에 지웁니다 (조회한 토큰만 삭제하며, 조회 이후 새로 발급되는 토큰까지 막는 처리는 탈퇴 · 비밀번호 변경 기능에서 함께 정함). 키 상세 → [Redis 키](../reference/redis-keys.md)

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

토큰 없이 통과하는 경로(상품 · 품종 조회, 로그인 · 로그아웃 · 재발급 · 가입 · 이메일 인증, 카카오 로그인, 택배 웹훅)는 **Gateway와 각 서비스 양쪽에** 설정합니다. 한쪽만 바꾸면 401이 나거나 검증 없이 통과합니다. 택배 웹훅은 토큰 대신 `X-Api-Key`로 확인합니다.

로그아웃(`POST /api/v1/auth/logout`)은 Gateway가 토큰 검사를 하지 않습니다. Access 토큰이 만료돼도 재발급 없이 로그아웃할 수 있게 하기 위해서입니다. member가 Refresh 쿠키로 이 기기의 Refresh를 지우고, 함께 온 Access 토큰이 서명이 맞고 아직 유효하면 블랙리스트에 넣습니다 (사용자 결정 2026-11-09). Access 토큰은 `Authorization: Bearer` 헤더로 받으며(쿠키 아님) 두 토큰 모두 선택입니다. 토큰이 없거나 유효하지 않으면 블랙리스트만 건너뛰고 200이며, Redis 처리 실패는 500입니다 (일부만 처리될 수 있어 클라이언트는 실패로 보고 재시도).

## 내부 API 보호

`/internal/**`은 회원 토큰 검사를 하지 않는 대신 세 겹으로 막습니다: Gateway 라우팅 제외 · 서비스 포트 외부 미개방 · `X-Internal-Key` 헤더 확인(`INTERNAL_API_KEY`, `common-security` 필터). 상세 → [서비스 간 REST 규칙](../conventions/internal-api.md)
