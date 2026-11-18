# 0023. 결제 시도를 먼저 커밋하고, 주문 트랜잭션 밖에서 결제를 부른다

> 상태: ✅ 확정 · 결정일 2026-11-17 · 결정한 사람: 주문 · 결제 담당 (Q41, #280)
> 대체: - · 대체됨: -

## 배경

기존 Market의 주문 생성 및 재결제 로직은 DB 트랜잭션 안에서 Product · Payment REST를 호출한다. `create()`는 주문 저장 · 상품 예약 · 결제를 하나의 트랜잭션에서 처리하므로, Payment에서 차감이 완료된 후 Market의 트랜잭션이 커밋되기 전에 장애가 발생하면 주문은 롤백되고 예치금만 차감될 수 있다. `repayment()`는 주문 행 잠금을 유지한 채 Payment REST를 호출한다. 호출 중 파드가 종료되면 결제 시도 기록과 진행 상태가 남지 않아, 실제 결제 결과를 모르는 주문이 만료될 수 있다(정책 4.4-3-1 위반). 이에 Q41에서 결제 시도를 먼저 커밋하고, DB 트랜잭션과 행 잠금을 해제한 뒤 Payment REST를 호출하는 B안을 선택했다.

## 결정

| 장치 | 내용 |
|---|---|
| 트랜잭션 | `create()`: TX1(주문 저장) → 예약 REST → TX2 → 결제 REST → TX3. `repayment()`: TX2 → 결제 REST → TX3. REST는 트랜잭션 · 행 잠금 밖 (`Idempotency-Key: orderId`) |
| 주문 상태 | `OrderStatus`에 `PAYMENT_PROCESSING`(호출 중 · 결과 불명) · `REVIEW_REQUIRED`(복구 5회 실패) 추가. |
| TX2 | 조건부 UPDATE: `PAYMENT_PENDING` · 생성 10분 이내면 `PAYMENT_PROCESSING`, `attempt_no + 1`, `attempted_at` 기록. TX2 선점 실패 시 Payment REST를 호출하지 않는다. 해당 주문이 만료되었거나 만료 대상이라면 기존 만료 처리와 Outbox 예약 해제 흐름을 통해 상품 예약을 정리한다. 중복 해제는 orderId 기준 멱등 처리한다 |
| TX3 | 조건부 UPDATE: `PAYMENT_PROCESSING` · 자기 `attempt_no`일 때만 반영 |
| 결과 | `PAID` · `ALREADY_PAID` → `PAID` + 기존 `orderPayment()` 같은 TX / 잔액 부족 → `PAYMENT_PENDING` / 확정 실패(지갑 없음 · 4xx · 연결 실패) → `PAYMENT_FAILED` + order-failed / 타임아웃 · 5xx → `PAYMENT_PROCESSING` 유지 |
| 예약 실패 | 기존 규칙 그대로: 409 · 연결 실패는 주문 실패만, 타임아웃은 주문 실패 + order-failed |
| Saga 복구 | 1분 주기, `attempted_at` 10초 지난 `PAYMENT_PROCESSING`을 `SKIP LOCKED`로 선점(`attempt_no` · `attempt_count` +1 커밋) 후 트랜잭션 밖에서 재호출. 복구의 연결 실패는 결과 불명. 5회 안 되면 `REVIEW_REQUIRED` |
| 만료 | 만료 스케줄러는 기존 `SELECT FOR UPDATE SKIP LOCKED` 방식을 유지한다. 주문 행 잠금 후 `PAYMENT_PENDING` 상태와 10분 경과 여부를 확인하고 만료 처리한다. `PAYMENT_PROCESSING` 주문은 만료하지 않는다. 결제 시작은 TX2의 CAS로 제어하므로 만료 처리까지 CAS로 변경하지 않는다. 시각은 모두 market 앱 시각 |
| 장바구니 | `create()`는 예약 실패 · 결제 확정 실패가 아니면 TX3 커밋 뒤 선택 항목을 삭제한다(잔액 부족, 결과 불명 포함, 기존 정책 유지). 삭제 실패가 주문 상태에 영향을 주지 않도록 별도 처리한다. 주문이 만료되거나, `repayment()` 또는 Saga 복구에서 확정 실패로 끝나면 TX3에서 `restoreItem`으로 복원한다(이미 담긴 상품은 건너뜀). `repayment()`의 결제 완료, 잔액 부족, 결과 불명과 Saga 복구 성공은 장바구니를 변경하지 않는다. |

## 고른 이유

- 시도를 먼저 커밋하므로 파드가 죽거나 TX3가 실패해도 복구가 주문을 찾고, 결과 불명 주문은 만료되지 않는다
- 결제를 기다리는 동안 DB 커넥션 · 행 잠금을 쥐지 않는다
- `OrderStatus`를 세분화하여 기존 만료 · 재결제의 `PAYMENT_PENDING` 검사가 결제 중 주문을 그대로 걸러 낸다
- 복구 작업은 선점 트랜잭션을 커밋한 뒤 외부 서비스를 호출한다. 결제 시도의 소유권은 별도의 `attempt_no`로 검증한다. 중복 차감은 payment 멱등(지갑 잠금 + ref 유니크)으로 막는다

## 다른 안

| 안 | 버린 이유 |
|---|---|
| 시도를 커밋한 뒤 잠금을 쥔 채 결제 (Q41 A) | 기다리는 동안 커넥션 · 잠금을 쥐고, TX2 뒤 잠금 빈틈 때문에 어차피 시간 규칙이 필요함 |
| `attempted_at`만으로 시도 구분 | 시각 동등 비교는 정밀도 차이에 약함 |
| 결과 불명 응답 뒤 `attempt_count` +1 | 호출 중 파드가 죽으면 세지 않아 계속 재시도할 수 있음 |
| 결제 조회 API | 정책 4.4에서 기각 |
| 만료 처리도 CAS로 변경 | 결제 시작을 TX2의 CAS로 제어하면 경합이 막힌다. 만료가 먼저 잠그면 TX2는 잠금 해제 후 조건을 재검사하여 UPDATE 결과가 0건이 되고, TX2가 먼저면 만료가 상태 확인에서 건너뛴다. 기존 만료 구조(잠금 → 장바구니 복원 → order-expired Outbox)를 그대로 쓸 수 있다 |

## 영향

주문 · 결제, 장애 처리, 스케줄러, market, 내부 API 탈퇴 확인(진행 중 상태 추가), 테스트 시나리오. 미정에서 Q41 삭제. 구현 · 런북 · 알림 지표는 #281
