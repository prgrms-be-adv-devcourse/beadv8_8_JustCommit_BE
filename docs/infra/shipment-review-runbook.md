# 택배 예약 확인 필요 배송 처리

> 언제 읽나: 「배송 예약 · 취소 지연」 알림이 `REVIEW_REQUIRED`로 왔거나, 택배사가 예약을 거절해 멈춘 배송을 다시 예약할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-27 · 관련: [market 정책](../services/market.md#정책), [스케줄러](../reference/schedulers.md), [관측](observability.md), #671

## 언제 생기나

택배 예약 큐가 택배사(404-express)에 예약을 보냈는데, 다시 보내도 같은 결과로 거절되는 응답을 받으면 배송을 `READY` → `REVIEW_REQUIRED`로 바꾸고 재시도를 멈춥니다.

| 택배사 응답 | `last_error` 시작 | 흔한 원인 |
|---|---|---|
| 400 `INVALID_FIELD_ERROR` · `BAD_REQUEST_DATA` | `PERMANENT` | 보내는 사람 · 받는 사람 값이 비었거나 형식이 틀림 (주소 · 전화번호) |
| 400 `INVALID_IDEMPOTENCY_KEY` | `PERMANENT` | `courier_order_no` 형식이 택배사 규칙과 다름 |
| 409 `IDEMPOTENCY_KEY_REUSED` | `PERMANENT` | 같은 주문번호로 이미 다른 내용의 예약이 택배사에 있음 |
| 409 `ORDER_ALREADY_CANCELLED` (배송이 `READY`일 때) | `ALREADY_CANCELLED` | market은 취소하지 않았는데 택배사에 이 주문번호 취소 기록이 있음 |

다음은 확인 필요로 바꾸지 않습니다.

| 응답 | 처리 |
|---|---|
| 5xx · 408 · 429 · 시간 초과 · 연결 실패 | `READY` 유지, 10초 → 30초 → 1분 뒤 다시 시도 |
| 401 `UNAUTHORIZED` · 404 | `READY` 유지, 다시 시도. ERROR 로그 「택배사 설정 확인 필요(API 키 · 주소)」 → market의 `custom.courier.base-url` · API 키 확인 |
| 409 `ORDER_ALREADY_CANCELLED` (배송이 이미 `CANCEL_PENDING`) | 예약을 보내는 사이 구매자가 취소한 경우. 택배 취소 큐가 `CANCELLED`로 맞춤 |

구매자 · 판매자 화면과 주문 모듈에는 `REVIEW_REQUIRED`가 `READY`로 보입니다. 구매자는 이 상태에서도 취소할 수 있고, 취소하면 `CANCEL_PENDING`으로 넘어가 택배 취소 큐가 처리합니다.

## 알림

| 신호 | 내용 |
|---|---|
| 지표 | `replant_shipment_stuck{status="REVIEW_REQUIRED"}` (확인 필요 배송 수, 10분 기다리지 않고 바로 셈) |
| 알림 규칙 | 「배송 예약 · 취소 지연」 → [알림 규칙](observability.md#알림-규칙) |
| 로그 | market ERROR 「택배사가 예약을 거절해 확인 필요로 멈춤」, `shipmentId`, `courierOrderNo`, `failure` |

## 확인 순서

| 순서 | 할 일 | 보는 곳 |
|---|---|---|
| 1 | ERROR 로그에서 `shipmentId`와 `failure`를 찾음 | Loki |
| 2 | 배송의 `status`, `courier_order_no`, `direction`, `last_error`, `attempt_count`를 읽음 | market DB `shipment` (읽기만) |
| 3 | 택배사에 그 주문번호의 예약 · 취소가 있는지 확인: `GET /api/v1/shipments/byOrderNo/{courierOrderNo}` | 404-express |
| 4 | 원인 데이터 확인: 판매자 기본 배송지(가는 길 보내는 사람, 반품 받는 사람), 주문 배송지(구매자) | member · market `orders` |

## 처리

| 원인 | 처리 |
|---|---|
| 주소 · 전화번호 값 문제 (400) | 판매자에게 기본 배송지 수정을 요청하거나 주문 배송지를 바로잡은 뒤 아래 SQL로 `READY`로 되돌림 |
| 택배사에 다른 내용의 예약이 있음 (`IDEMPOTENCY_KEY_REUSED`) | 3번 조회 결과가 맞는 예약이면 택배사 웹훅으로 상태가 진행되므로 기다림. 틀린 예약이면 택배사에서 취소한 뒤 배송 담당과 새 주문번호 발급 여부를 정함 |
| 택배사에만 취소 기록 (`ALREADY_CANCELLED`) | 구매자에게 확인해 주문을 취소하면 구매자 취소로 처리(`CANCEL_PENDING` → 택배 취소 큐 → 환불). 배송을 이어가야 하면 택배사(404-express) 쪽 취소 기록 처리를 배송 담당과 정한 뒤 `READY`로 되돌림 |

`READY`로 되돌리는 SQL (조건부, 1행만 바뀌어야 함):

```sql
update shipment
set status = 'READY', next_attempt_at = null, attempt_count = 0, last_error = null, booking_request = null, version = version + 1
where id = :shipmentId and status = 'REVIEW_REQUIRED';
```

`booking_request`(첫 요청 본문, #672)를 비워야 고친 주소로 본문을 새로 만듭니다. 되돌리면 10초 안에 택배 예약 큐가 같은 주문번호로 다시 예약합니다. 처리하면 지표 값이 줄어 알림이 멈춥니다.
