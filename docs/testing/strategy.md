# 테스트 전략

> 언제 읽나: 테스트를 어디에 어떻게 넣을지 정할 때
> 상태: 🟨 제안 · 마지막 수정 2026-11-08 · 관련: 노션 기획안 「7. 테스트 시나리오」, [노션 「테스트 시나리오」 3절 WireMock 사용법](https://www.notion.so/410f93f59cb0808e85ddcfccfaa34bd6)

## 단계

| 단계 | 언제 | 무엇을 실행 | 어디서 | 실패하면 |
|---|---|---|---|---|
| ① 로컬 | PR 올리기 전 | 바뀐 서비스 테스트 (예: `./gradlew :services:market-service:test`) | 개발자 PC | 고친 뒤 PR |
| ② PR CI | PR 열 때 · 푸시할 때 | 경로 필터로 바뀐 서비스만 단위 · 통합. `common/`이 바뀌면 전체 | GitHub Actions (필수 체크 `build`) | 머지 불가 |
| ③ CD | develop 머지 후 | 바뀐 서비스 이미지 → GHCR → 서버 교체 | GitHub Actions → EC2 | 이전 이미지 태그로 되돌림 |
| ④ 야간 · 수동 E2E | 매일 새벽(schedule) + 필요할 때(workflow_dispatch) | `e2e` 모듈: E2E · 장애 시나리오 (Docker Compose 전체 실행, Toxiproxy 지연). 실측값은 `results.json` Artifact | GitHub Actions | 이슈 등록 |
| ⑤ 시연 전 체크 | 발표 전 | 수동 시나리오 S-I03 · S-J04 · S-K01 · S-K02 | 운영 서버 · Grafana | 시연 전에 수정 |
| 결과 기록 | ④ · ⑤ 후 | [시나리오](scenarios.md) 결과 칸에 날짜와 ✅ / ❌, 실측값 | 이 레포 | ❌면 이슈 등록 |

🟨 워크플로 이름 · 실행 시각 · 태그 이름은 CI 담당이 확정합니다 (#256).

## ID와 이름 규칙

| 규칙 | 내용 |
|---|---|
| ID | `S-{그룹}{번호}` — A 인증 · B 회원 · C 상품 · D 주문/결제 · E 취소/환불 · F 배송 · G 구매 확정/정산 · H 이벤트 · I 알림 · J 장애 격리 · K 관측 · P 충전 |
| 테스트 이름 | `@DisplayName("[S-D02] 예치금 부족 → 결제 대기 유지")` — 레포에서 ID로 검색하면 테스트 위치가 나옴 |
| 위치 | 단위 · 통합 = `services/{서비스}/src/test`, E2E · 장애 = `e2e` 모듈, 공통 테스트 도구 = 공통 모듈 `testFixtures` |
| 시간 | `Clock` 등으로 고정, `@DisplayName`은 한국어 |
| CI 결과 | GitHub Actions 테스트 리포트에 DisplayName이 그대로 나와 실패한 ID를 바로 확인 |

## 도구

| 도구 | 쓰는 곳 |
|---|---|
| Testcontainers | 통합 테스트의 PostgreSQL · Kafka · Redis |
| WireMock | 다른 서비스 · 토스 · 택배사 응답 흉내 |
| Toxiproxy | E2E에서 지연 · 끊김 주입 |
| JUnit `@Tag("e2e")` · `@Tag("chaos")` | 골라 실행 (`./gradlew :e2e:test`) |
| k6 | 부하 테스트 → [관측](../infra/observability.md#부하-테스트) |

**WireMock 규칙** — 내 서비스가 다른 서비스 · 외부 API를 REST로 부를 때만 씁니다. 남이 내 API를 부르는 경우는 MockMvc · WebTestClient, 이벤트를 받는 경우는 테스트 Kafka에 직접 넣고, 여러 서비스를 진짜로 이을 때는 E2E입니다. 예시 코드 → 노션 「테스트 시나리오」 3절

| 규칙 | 내용 |
|---|---|
| 스텁 위치 | 부르는 서비스 테스트 안 `services/{서비스}/src/test/resources/wiremock/{상대}/`. 공통 모듈로 빼지 않음 (의존성 `org.wiremock:wiremock-standalone` 버전만 `libs.versions.toml`에 한 번) |
| 주소 바꿔 끼우기 | 테스트 클래스마다 빈 포트로 띄우고 `spring.http.serviceclient.{그룹}.base-url`을 WireMock 주소로 (그룹 이름은 `internal-api.groups`의 이름). 응답 타임아웃은 `read-timeout` 500ms + 지연 1초로 짧게 |
| 계약 원본 | 경로 · 필드 · 에러 code는 [내부 API 목록](../reference/internal-apis.md)과 같게. 계약이 바뀌면 제공 담당이 같은 PR에서 문서와 부르는 쪽 응답 JSON을 함께 고침 |

| 흉내 낼 응답 | WireMock | 기대 예외 · 재시도 횟수 |
|---|---|---|
| 성공 | `okJson(본문)` | 정상 진행 |
| 업무 실패 (4xx) | `withStatus(409)` + `ApiResponse` 본문 | `InternalApiErrorException`(status · code), 재시도 0번 |
| 서비스 불가 (503) | `serviceUnavailable()` | `InternalApiErrorException`(503), 재시도 0번 |
| 응답 타임아웃 | `withFixedDelay(1000)` | `InternalApiResultUnknownException` → 결과 불명 (보상 안 함) |
| 연결 실패 | WireMock 정지 · 닫힌 포트 | `InternalApiUnavailableException`, 0.2초 뒤 1번만 재시도 |
| 응답 중 끊김 | `withFault(CONNECTION_RESET_BY_PEER)` | 결과 불명 (🟨 `common-web` 동작 확인 후 확정) |
| 처음 실패, 다시 하면 성공 | `inScenario` 상태 전환 | 스케줄러 재시도 · 결과 불명 재확인 후 진행 |

| verify | 확인할 것 |
|---|---|
| 호출 수 | `verify(n, ...)` — 중복 요청 · 재시도 횟수, 안 불렸는지(`verify(0, ...)`) |
| 헤더 | `Idempotency-Key`(자동으로 안 붙으므로 각 클라이언트가 넣었는지) · `X-Internal-Key` |
| 한계 | 적어 둔 대로만 답하므로 계약 어긋남은 E2E(야간)로 잡음. 계약 테스트(Spring Cloud Contract)는 범위 밖 |
