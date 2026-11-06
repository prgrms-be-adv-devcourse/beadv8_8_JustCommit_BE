# DB · Flyway

> 언제 읽나: 테이블을 추가 · 변경하거나 Flyway 마이그레이션을 작성할 때, 서비스별 DB 구성을 확인할 때
> 상태: 🟨 제안 · 마지막 수정 2026-11-06 · 관련: Miro 5, 노션 기획안 「DB 분리」

## 서비스별 DB

PostgreSQL **인스턴스 1개** 안에서 서비스마다 DB와 계정을 나눕니다. 다른 서비스 DB는 읽지 않습니다.

| DB | 소유 서비스 | 테이블 |
|---|---|---|
| member | member | → [member 카드](../services/member.md#소유-테이블) |
| product | product | → [product 카드](../services/product.md#소유-테이블) |
| market | market (파드 2개가 같은 DB) | → [market 카드](../services/market.md#소유-테이블) |
| payment | payment | → [payment 카드](../services/payment.md#소유-테이블) |
| settlement | settlement | → [settlement 카드](../services/settlement.md#소유-테이블) |
| notification | notification | → [notification 카드](../services/notification.md#소유-테이블) |
| ai | ai-service | pgvector (Sprint 2) |

⬜ DB 이름 · 계정 이름 규칙 미정

## 분리 순서

⬜ 미정 (#253). 서비스 분리 Task 순서와 맞춥니다.

## 기존 데이터

운영 데이터는 더미라 **전부 지우고 시작**합니다. 데이터 이전은 없습니다 (11/3 결정).

## Flyway 규칙

- 앱이 뜰 때 Flyway가 실행되고, 운영은 `ddl-auto: validate`로 바꿉니다 (지금은 local · prod 모두 `update`).
- V1은 지금 엔티티 기준 스키마입니다.
- ⬜ 파일 이름 · 버전 규칙 · 적용 절차 미정 (#253)
