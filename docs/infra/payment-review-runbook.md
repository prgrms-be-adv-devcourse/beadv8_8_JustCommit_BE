# 결제 확인 필요 주문 처리

> 언제 읽나: `REVIEW_REQUIRED` 알림을 받았거나, 결제 결과를 확정하지 못한 주문을 처리할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-18 · 관련: [ADR 0023](../decisions/0023-order-payment-transaction-saga.md), [주문 · 결제](../flows/order-payment.md#멈춘-주문-복구), [관측](observability.md)

## 언제 생기나

| 경우 | 주문 상태 |
|---|---|
| Saga 복구가 결제를 5번 다시 불러도 결과를 모름 (응답 타임아웃, 5xx, 연결 실패) | `PAYMENT_PROCESSING` → `REVIEW_REQUIRED` |
| 5번째 복구를 선점한 뒤 결과를 반영하지 못하고 10초가 지남 (파드 종료 등) | `PAYMENT_PROCESSING` → `REVIEW_REQUIRED`, 결제를 다시 부르지 않음 |

`REVIEW_REQUIRED`가 되면 `attempt_no`가 올라가, 그 뒤 늦게 온 결제 응답은 반영되지 않습니다. 주문은 만료되지 않고, 상품 예약도 풀리지 않은 채 남습니다. 구매자는 이 주문이 끝나기 전까지 탈퇴할 수 없습니다.

## 알림

| 신호 | 내용 |
|---|---|
| 지표 | `market_orders_review_required` (확인 필요 주문 수). Saga 복구가 1분마다 갱신, 파드 2개가 같은 값을 내므로 `max()`로 봄 |
| 알림 규칙 | `max(market_orders_review_required) > 0` → [알림 규칙](observability.md#알림-규칙) |
| 로그 | market ERROR 「결제 결과를 확정하지 못해 확인 필요로 전환」, key-value `orderId`, `attemptNo`, `attemptCount`, `lastError`, `reason` |

## 확인 순서

| 순서 | 할 일 | 보는 곳 |
|---|---|---|
| 1 | ERROR 로그에서 `orderId`와 `lastError`(`TIMEOUT`, `HTTP_500_...`, `CONNECTION_FAILED` 등)를 찾음 | Loki |
| 2 | 주문의 `status`, `attempt_no`, `attempt_count`, `attempted_at`, `last_error`, `total_amount`를 읽음 | market DB `orders` (읽기만) |
| 3 | 그 주문으로 돈이 빠졌는지 확인: `ref_type = 'PAYMENT'`, `ref_id = orderId`인 거래가 있는지 | payment DB `wallet_transaction` (읽기만) |

## 처리

| 3번 결과 | 처리 |
|---|---|
| 결제 성공 (거래가 있음) | 환불을 끝낸 뒤 주문 취소 |
| 결제 안 됨 또는 실패 확정 (거래가 없음) | 환불 없이 `PAYMENT_FAILED`로 끝냄 |

`PAYMENT_FAILED`와 취소된 주문은 어떤 스케줄러도 다시 집지 않습니다. 처리하면 지표 값이 줄어 알림이 멈춥니다.

## 운영 전에 갖출 것

이 런북의 지표와 알림은 관측 구성(#301)이 끝나야 동작합니다.

| 항목 | 지금 |
|---|---|
| `/actuator/prometheus` 접근 | market은 노출하지만 공통 보안 설정이 `/actuator/health/**`만 열어 401 |
| 운영 Prometheus 수집 | 설정 없음 (`perf/monitoring`은 부하 측정용) |
| Grafana 알림 규칙 | 없음 |
