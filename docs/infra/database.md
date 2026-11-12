# DB · Flyway

> 언제 읽나: 테이블을 추가 · 변경하거나 Flyway 마이그레이션을 작성할 때, 서비스별 DB 구성을 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-12 · 관련: Miro 5, 노션 기획안 「DB 분리」

## 서비스별 DB

PostgreSQL **인스턴스 1개** 안에서 서비스마다 Database와 계정을 나눕니다. 다른 서비스 DB는 읽지 않고, 다른 서비스 DB를 참조하는 물리 FK도 두지 않습니다. (#253 팀 확인, PR #269 구현)

| DB | 계정 | 소유 서비스 | 테이블 |
|---|---|---|---|
| `member_db` | `member_app` | member | → [member 카드](../services/member.md#소유-테이블) |
| `product_db` | `product_app` | product | → [product 카드](../services/product.md#소유-테이블) |
| `market_db` | `market_app` | market (파드 2개가 같은 DB) | → [market 카드](../services/market.md#소유-테이블) |
| `payment_db` | `payment_app` | payment | → [payment 카드](../services/payment.md#소유-테이블) |
| `settlement_db` | `settlement_app` | settlement | → [settlement 카드](../services/settlement.md#소유-테이블) |
| `notification_db` | `notification_app` | notification | → [notification 카드](../services/notification.md#소유-테이블) |
| `ai_db` | `ai_app` | ai-service | pgvector 확장 `vector` → [ai 카드](../services/ai.md#소유-테이블) |

| 권한 | 규칙 |
|---|---|
| 접속 | DB마다 PUBLIC의 `CONNECT` · `TEMPORARY`를 회수하고 자기 계정에만 `CONNECT`를 줌 → 다른 서비스 DB 접속 거부 |
| DDL | 처음에는 앱 계정이 마이그레이션 DDL 권한도 가짐. Flyway 전용 계정 분리는 이후 개선 |
| 로컬 생성 | `docker/postgres/01-service-databases.sql` (빈 볼륨 첫 시작 때 자동, 여러 번 실행해도 안전) |
| 운영 생성 | 같은 SQL을 인프라 담당이 운영 DB에 적용, 비밀번호는 환경변수 |
| 확장 (`vector`) | superuser만 만들 수 있어 앱 계정이 아니라 위 SQL이 `ai_db`에 만듦. ai Flyway V1은 같은 문장(`IF NOT EXISTS`)이라 이미 있으면 지나감. 이미지는 `pgvector/pgvector:pg17` (로컬 compose · Testcontainers) |

## 분리 순서

전체 순서를 따로 두지 않고, **각 서비스 분리 Feature에서 앱과 DB를 함께** 나눕니다 (예: 정산 #238 · #240). 기반(서비스별 DB · 계정 · Flyway 의존성)은 #231 · PR #269에서 먼저 만들었습니다.

## 기존 데이터

운영 데이터는 더미라 **전부 지우고 시작**합니다. 데이터 이전은 없습니다 (11/3 결정).

| 초기 데이터 | 만드는 방법 |
|---|---|
| 품종 등 기준 데이터 | 해당 서비스의 Flyway 버전 파일 |
| 시스템 지갑 `HOLDING` · `REVENUE` | payment Flyway 버전 파일로 생성 + 유형별 1개만 있도록 DB 제약조건 ([ADR 0006](../decisions/0006-system-wallet-type.md)) |
| member 시스템 회원 계정 | MSA에서는 필요 없음 → 회원 서비스 분리 때 정리 |

세미의 `justcommit` DB는 전환 기간에만 남고, 서비스 분리가 끝나면 정리합니다.

## Flyway 규칙

| 항목 | 규칙 |
|---|---|
| 위치 | `services/<service>/src/main/resources/db/migration` |
| 파일 이름 | `V{번호}__{설명}.sql`, 서비스마다 V1부터, 설명은 영어 snake_case. 예: `V1__create_settlement.sql` |
| V1 내용 | 지금 엔티티 기준 테이블 · PK · UNIQUE · NOT NULL · 인덱스 · IDENTITY · 같은 DB 안 FK |
| 수정 금지 | 공유 환경에 적용된 파일은 고치지 않고 새 버전을 추가 |
| 번호 충돌 | 아직 공유 환경에 적용되지 않은 파일만, 나중에 머지하는 쪽이 번호를 조정 |
| 실행 | 로컬 · 운영 모두 서비스가 시작할 때 Flyway 자동 실행 → `ddl-auto: validate`로 검증 → 앱 준비 완료 |
| 실패 | 마이그레이션이 실패하면 서비스 시작을 멈춤. 운영 적용 전 SQL 검토와 DB 백업 |
| 의존성 | `spring-boot-starter-flyway` · `flyway-database-postgresql` (Boot 4) |
