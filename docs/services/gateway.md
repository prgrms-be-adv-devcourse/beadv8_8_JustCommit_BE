# Gateway (Spring Cloud Gateway)

> 언제 읽나: Gateway (Spring Cloud Gateway) 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-06 · 관련: Miro 4 · 6 · 14, A2, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 라우팅 | 외부 경로 `/api/v1/**`를 서비스별로 전달 (market은 K3s Service 이름). `/internal/**`은 라우팅하지 않음 |
| 1차 인증 | RS256 공개키로 서명 · 만료 검증 + 블랙리스트 · 회원 단위 폐기 확인, ADMIN 경로 권한 확인 → [인증 규칙](../conventions/auth.md) |
| 토큰 전달 | 검증한 토큰을 그대로 서비스에 전달 (`X-User-*` 헤더 안 씀) |
| 요청 수 제한 | SCG `RedisRateLimiter` (IP 기준, Nginx가 덮어쓴 `X-Forwarded-For`) |
| 타임아웃 · 실패 응답 | 경로별 타임아웃, 서킷 브레이커 없음 → [장애 처리 값](../conventions/resilience.md) |
| CORS | Gateway에서만 처리 |

K3s에 파드 2개, 롤링 배포. 필터 순서는 접근 로그 → 인증 → 라우팅입니다. WebFlux 버전입니다.

## 소유 테이블

없음 (DB 없음). Redis 키만 읽음 → [Redis 키](../reference/redis-keys.md)

## 외부 API

자체 API 없음. 모든 외부 API를 라우팅 → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

없음

## 호출하는 내부 API

없음

## 발행 이벤트

없음

## 구독 이벤트

없음

## 스케줄러

없음

## 정책

| 정책 | 내용 |
|---|---|
| 공개 경로 | 상품 · 품종 조회, 로그인 · 가입 · 이메일 인증, 카카오 로그인(`/oauth2/**` · `/login/oauth2/**` → member, `trusted-proxies`), 택배 웹훅(`X-Api-Key`), 판매자 상점. 서비스 쪽에도 같은 목록을 설정 |
| 재시도 | 하지 않음 (POST 중복 방지) |
| 요청 수 제한 값 | 로그인 IP당 1분 5회, 인증번호 발송 IP당 1분 5회. ⬜ 카카오 로그인 시작 · 중복 확인 · 초과 응답(429 제안)은 미정 |
| 실패 응답 | 🟨 503 / 504 / 502 / 500 구분 (공통 담당 회신 대기) |

## 테스트 시나리오

S-A02 · S-A03 · S-A04 · S-A05 · S-A08 · S-I03 · S-J01 · S-J02 → [테스트 시나리오](../testing/scenarios.md)
