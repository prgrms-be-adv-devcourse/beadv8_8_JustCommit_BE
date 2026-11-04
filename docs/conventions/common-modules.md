# 공통 모듈

> 언제 읽나: common-* 모듈에 코드를 넣거나 쓸 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro 12, 노션 기획안 「공통 코드 정리」

## 모듈 목록

| 모듈 | 들어가는 것 | 왜 |
|---|---|---|
| `common-core` | `ApiResponse`, `ErrorCode` (서블릿 · 리액티브 의존 없음) | Gateway(WebFlux)도 같은 형식으로 401 · 403 · 503을 내야 함 |
| `common-web` | `PagingRequest` · `PagingResponse`, `BusinessException`, `GlobalExceptionHandler`, `CommonErrorCode`, `RestClient` 공통 설정(연결 1초 · 응답 3초, `Idempotency-Key` · `X-Internal-Key` 자동), Resilience4j `internal` 기본값 | 모든 서비스가 같은 응답 · 에러 형식 |
| `common-security` (공유) | `AuthMember`, 공개키 토큰 검증, `TokenException` · `TokenType` | 검증 로직 하나로 |
| `common-security` (서블릿) | `JwtAuthenticationFilter`, 401/403 핸들러, `SecurityConfig` 기본 규칙, `/internal/**` 내부 키 필터, `UserIdMdcFilter` | 서비스 2차 검증 |
| `common-security` (WebFlux) | Gateway JWT 1차 검증 `GlobalFilter` | Gateway 1차 검증 |
| `common-messaging` | `EventEnvelope`, Outbox 저장 · 커밋 후 발행 · 재전송 스케줄러, `traceparent` 헤더, `@RetryableTopic` · `@DltHandler` 기본 설정, 토픽 이름 상수 | 유실 · 중복 대응을 한 방식으로 |
| `common-logging` | `common-logging.yml`(JSON · 레벨 · traceId · Kafka 전파), actuator · micrometer-tracing 의존성, 개인정보 마스킹 | Loki에서 한 번에 검색 |

## 서비스별로 두는 것

| 코드 | 이유 |
|---|---|
| `BaseTimeEntity`, `JpaAuditingConfig`, `OpenApiConfig`, 공개 경로 목록 | 서비스마다 다르고 몇 줄이라 복사가 더 쌈 |
| 이벤트 record (`PurchaseConfirmed` 등) | 공유 jar에 두면 필드 하나 바꿀 때 받는 서비스를 모두 다시 배포해야 함 |
| 토큰 발급 (`JwtProvider` 발급 부분, `SignupClaims`, `CookieOAuth2AuthorizationRequestRepository`) | member만 함 |

## 규칙

1. 공통 모듈에 비즈니스 로직을 넣지 않습니다. 커질수록 서비스가 다시 묶입니다.
2. 서비스끼리 Gradle 의존을 걸지 않습니다. 서비스는 공통 모듈만 의존합니다 (세미의 Modulith 경계 테스트를 대신하는 규칙).
3. `common/`이 바뀌면 CI/CD가 모든 서비스를 빌드 · 배포합니다.

레포 구조:

```
replant/
├── common/        # common-core · web · security · messaging · logging
├── gateway/
├── services/      # member · product · market · payment · settlement · notification · ai
├── infra/         # docker-compose.large.yml · medium.yml · nginx/ · k8s/
├── scripts/
└── docs/
```
