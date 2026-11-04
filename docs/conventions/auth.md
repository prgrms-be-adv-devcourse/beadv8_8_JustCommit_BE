# 인증 · 인가 규칙

> 언제 읽나: 토큰 · 권한 · 공개 경로 · 로그아웃을 다룰 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro 6, A1, 노션 기획안 「인증 · 인가」

## 토큰 발급과 검증

| 항목 | 내용 |
|---|---|
| 서명 | RS256. 개인키는 member만 가짐 (`JWT_PRIVATE_KEY`), 공개키는 Gateway와 모든 서비스가 환경변수로 받음 (`JWT_PUBLIC_KEY`, JWKS 안 씀) |
| 수명 | Access 30분, Refresh 1일 (재발급 때 새로 시작) |
| 1차 검증 (Gateway) | 서명 · 만료 · 토큰 type + `auth:blacklist` · `auth:revoked-member` 확인 |
| 2차 검증 (각 서비스) | 공개키로 서명 · 만료만. 회원 정보는 토큰에서 꺼냄 (`AuthMember`) |
| 전달 | Gateway는 토큰을 그대로 넘김. `X-User-*` · `X-Member-*` 헤더는 쓰지 않음 |
| 왜 이중 검증인가 | 포트가 실수로 열려도 위조 토큰이 통과하지 않게 (심층 방어) → [ADR 0001](../decisions/0001-scg-rs256-double-validation.md) |

코드 위치: 검증 로직 · `AuthMember` → `common-security`, 서비스 필터 → `common-security`(서블릿), Gateway 필터 → `common-security`(WebFlux), 발급 → member → [공통 모듈](../conventions/common-modules.md)

## 토큰 폐기

| 상황 | Redis 키 | 효과 | TTL |
|---|---|---|---|
| 로그아웃 | `auth:blacklist:{토큰 해시}` | 그 Access 토큰 하나만 거절 | 토큰 남은 수명 |
| 탈퇴 · 비밀번호 변경 | `auth:revoked-member:{memberId}` = 끊은 시각(초) | `iat`가 그 시각보다 이전인 토큰 모두 거절 (모든 기기) | 30분 (Access 수명) |
| 전체 로그아웃 | 회원별 Refresh 해시 Set (⬜ 이름 미정) | 그 회원의 Refresh 토큰 일괄 삭제 | 1일 |

쓰는 쪽은 member, 읽는 쪽은 Gateway입니다. 키 상세 → [Redis 키](../reference/redis-keys.md)

## 권한

| 권한 | 할 수 있는 일 |
|---|---|
| 비회원 | 상품 · 품종 조회 |
| `MEMBER` | 구매 |
| `SELLER` | 판매 + 구매 (판매자 등록 후 토큰 재발급해야 반영) |
| `ADMIN` | 정산 관리 · 사진 수동 검수 · 전체 기능 |

상위 권한은 하위 권한의 기능을 모두 씁니다.

## 공개 경로

토큰 없이 통과하는 경로(상품 · 품종 조회, 로그인 · 가입 · 이메일 인증, 카카오 로그인, 택배 웹훅)는 **Gateway와 각 서비스 양쪽에** 설정합니다. 한쪽만 바꾸면 401이 나거나 검증 없이 통과합니다. 택배 웹훅은 토큰 대신 `X-Api-Key`로 확인합니다.

## 내부 API 보호

`/internal/**`은 회원 토큰 검사를 하지 않는 대신 세 겹으로 막습니다: Gateway 라우팅 제외 · 서비스 포트 외부 미개방 · `X-Internal-Key` 헤더 확인(`INTERNAL_API_KEY`, `common-security` 필터). 상세 → [서비스 간 REST 규칙](../conventions/internal-api.md)
