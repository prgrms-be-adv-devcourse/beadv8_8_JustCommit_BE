# payment 서비스

> 언제 읽나: payment 서비스 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro 5 · 13, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 예치금 | 지갑 · 거래 내역, 토스페이먼츠 충전 · 승인, 인출 |
| 결제 · 환불 | market의 내부 REST로 잔액 차감 · 상품 금액 환불 · 배송비 환불 |
| 정산금 지급 | settlement의 내부 REST로 판매자 지갑에 지급 |
| 배송비 매출 | `ShipmentPickedUp`을 받아 배송비를 매출 지갑으로 |
| 시스템 지갑 | 홀딩 · 매출 지갑을 `wallet_type`으로 직접 소유 (회원 계정 없음) |

## 소유 테이블

| 테이블 | 설명 |
|---|---|
| `WALLET` | 지갑 (`wallet_type`: `MEMBER` / `HOLDING` / `REVENUE`) |
| `WALLET_TRANSACTION` | 거래 내역 (거래 유형 + 대상 ID UK) |
| `CHARGE` | PG 충전 |
| `CASH_OUT` | 인출 |
| `MEMBER_REPLICA` | 회원 복제본 (계좌 포함) |
| `OUTBOX` | 발행 대기 이벤트 |

## 외부 API

`wallet` · `wallet/transactions` · `wallet/charges` · `wallet/charges/{id}/confirm` → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

| API | 부르는 서비스 | 멱등 키 |
|---|---|---|
| `POST /internal/v1/payments` | market | orderId |
| `POST /internal/v1/refunds/order-items/{orderItemId}` | market | orderItemId |
| `POST /internal/v1/refunds/shipping-fees/{sellerOrderId}` | market | sellerOrderId |
| `POST /internal/v1/settlement-payouts` | settlement | settlementId |

상세 → [내부 API 목록](../reference/internal-apis.md)

## 호출하는 내부 API

없음 (외부: 토스페이먼츠 연결 3초 · 승인 응답 65초 → [장애 처리 값](../conventions/resilience.md))

## 발행 이벤트

없음 (출금 · 충전 알림을 만들지 않아 `payment.events.v1` 없음)

## 구독 이벤트

| 이벤트 | 받아서 하는 일 | 멱등 처리 |
|---|---|---|
| `member.events.v1` | `MEMBER_REPLICA` 저장 · 갱신, `MemberCreated`이면 지갑 생성 | memberId upsert, 지갑 1개 |
| `member.account.events.v1` | 정산금 지급 계좌 갱신 | memberId upsert |
| `shipment.events.v1` `ShipmentPickedUp` | 배송비 매출 처리 | 거래 내역 UK (sellerOrderId) |

## 스케줄러

Outbox 재전송 · 결과 불명 결제 조회 → [스케줄러 표](../reference/schedulers.md)

## 정책

세미 프로젝트 정책 문서(`docs/policy.md`, 삭제됨)에서 옮긴 확정 정책입니다.

1. 예치금은 10,000원 단위로 충전한다.
2. 최소 충전 금액은 10,000원이다.
3. 충전은 취소할 수 없다. 대신 예치금 범위 안에서 인출할 수 있다.

## 테스트 시나리오

S-D01 ~ S-D06 · S-G03 · S-G04 → [테스트 시나리오](../testing/scenarios.md)
