# 테스트 전략

> 언제 읽나: 테스트를 어디에 어떻게 넣을지 정할 때
> 상태: 🟨 제안 · 마지막 수정 2026-11-04 · 관련: 노션 기획안 「7. 테스트 시나리오」

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
| ID | `S-{그룹}{번호}` — A 인증 · B 회원 · C 상품 · D 주문/결제 · E 취소/환불 · F 배송 · G 구매 확정/정산 · H 이벤트 · I 알림 · J 장애 격리 · K 관측 |
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
