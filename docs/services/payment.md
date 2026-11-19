# payment 서비스

> 언제 읽나: payment 서비스 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-20 · 관련: Miro 5 · 13, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 지갑 | 회원 지갑 1개 = 잔액(`balance`) + 미수금(`receivable`). 충전금과 정산금을 나누지 않음. 시스템 지갑 `HOLDING` · `REVENUE`는 `wallet_type`으로 직접 소유 |
| 충전 | 토스페이먼츠. 충전 취소는 하지 않음 |
| 결제 · 환불 | market의 내부 REST로 잔액 차감 · 상품 금액 · 배송비 환불 |
| 정산 지급 | settlement의 내부 REST로 지급(0 이상) 또는 회수(음수), 수수료와 배송비, 판매자 부담금은 `REVENUE`로 |
| 인출 | 잔액을 member에서 조회한 계좌로 |
| 대사 | 토스 거래와 내부 충전 기록 매일 대조 |

## 소유 테이블

| 테이블 | 설명 |
|---|---|
| `WALLET` | 지갑 (`wallet_type`: `MEMBER` / `HOLDING` / `REVENUE`, `balance` 잔액, `receivable` 미수금) |
| `WALLET_TRANSACTION` | 거래 내역 (거래 유형 + 대상 ID UK). `balance_after`(처리 후 잔액), `receivable_after`(처리 후 미수금, 어느 거래로 미수금이 바뀌었는지 추적) |
| `CHARGE` | 충전 (`PENDING`, `APPROVED`, `REVIEW_REQUIRED` 등). 복구 스케줄러용 `attempt_count`(조회 실패 횟수), `next_attempt_at`(다음 조회 시각) |
| `CASH_OUT` | 인출 (계좌 암호화 저장) |
| `MEMBER_REPLICA` | 회원 복제본 |
| `OUTBOX` | ⬜ 발행하는 이벤트가 없어 필요 여부 확인 |

## 외부 API

`wallet` · `wallet/transactions` · `wallet/charges` · `wallet/charges/{id}/confirm` · `wallet/cashOuts` → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

| API | 부르는 서비스 | 멱등 키 |
|---|---|---|
| `POST /internal/v1/payments` | market | orderId |
| `POST /internal/v1/refunds/order-items/{orderItemId}` | market | orderItemId |
| `POST /internal/v1/refunds/shipping-fees/{sellerOrderId}` | market | sellerOrderId |
| `POST /internal/v1/settlementPayouts` | settlement | settlementId |
| `GET /internal/v1/payments/members/{memberId}/withdrawalCheck` | member | – |

상세 → [내부 API 목록](../reference/internal-apis.md)

## 호출하는 내부 API

| 대상 | 용도 |
|---|---|
| member | `GET /internal/v1/members/{memberId}/account` 계좌 조회 (인출 신청 때) |
| 토스페이먼츠 | 충전 승인, 조회 (연결 3초 · 응답 60초) |

## 발행 이벤트

없음 (출금 · 충전 알림을 만들지 않음)

## 구독 이벤트

| 이벤트 | 받아서 하는 일 | 멱등 처리 |
|---|---|---|
| `member.events.v1` ✅ | `MEMBER_REPLICA` 저장 · 갱신, 탈퇴 표시, `MemberCreated`이면 지갑 생성 (없으면 필요한 시점에 생성) | memberId upsert(저장된 `occurredAt`보다 오래된 이벤트는 무시), 지갑 1개 |

배송비 매출은 이벤트가 아니라 정산 지급 때 옮깁니다. 계좌 이벤트는 없습니다.

## 스케줄러

토스 결과 불명 조회(5분 간격, 조회 실패 3회 또는 충전 생성 60분 뒤 미확정 → `REVIEW_REQUIRED`), 토스 대사(하루 1번) → [스케줄러 표](../reference/schedulers.md)

## 정책

노션 「정책 정리」 4절 기준입니다.

| 주제 | 규칙 |
|---|---|
| 조회 | 예치금 · 거래 내역 조회는 지갑을 만들지 않음 (없으면 잔액 0 · 빈 목록) |
| 충전 | 10,000원 단위, 최소 10,000원. 승인되면 잔액 증가. 결과를 모르면 `PENDING`("충전 처리 중", 예치금 미반영) → 복구 스케줄러가 토스 결제 조회로 `APPROVED`, `FAILED`, `REVIEW_REQUIRED` 중 하나로 확정. `FAILED`는 토스가 `ABORTED`, `EXPIRED`를 돌려줄 때만. 자동으로 확정하지 못하면 `REVIEW_REQUIRED`(사람이 확인, 예치금 미반영, 승인을 다시 요청하면 409 `CHARGE_PROCESSING`) → [스케줄러 표](../reference/schedulers.md) |
| 결제 | 주문 금액만큼 차감해 `HOLDING`으로, 같은 주문은 한 번만 (같은 orderId로 다시 오면 차감하지 않고 `ALREADY_PAID`). 결제 결과 조회 API는 두지 않음 → [주문 · 결제](../flows/order-payment.md) |
| 수거 전 취소 환불 | 취소된 상품 금액을 `HOLDING` → 구매자 지갑, 전부 취소면 배송비도. 판매자 거절 · 자동 거절도 같음. 각각 한 번만 (같은 키면 `ALREADY_REFUNDED`로 처음 결과, 확정 실패는 4xx) |
| 반품 환불 | 반품 택배가 판매자에게 도착하면 즉시 |
| 정산 지급 | 판매액(정산금 + 수수료 + 판매자 부담금)을 `HOLDING`에서 꺼냄(0원이면 이동 없음). 정산금이 0 이상이면 판매자 잔액에 입금, 음수면 판매자 잔액에서 min(잔액, 부족액)을 회수(`SETTLEMENT_COLLECTION`)하고 못 받은 금액은 미수금. `REVENUE`에는 수수료(`SETTLEMENT_FEE`), 판매자 부담금 중 실제로 받은 금액(`SELLER_BURDEN`, 미수금으로 남은 금액 제외), 배송비(`SHIPPING_FEE`). 배송비는 `sellerOrderId`로 한 번만, 이미 옮겼거나 구매자에게 환불된 배송비는 건너뛰고 지급은 계속. 같은 정산은 한 번만(판매자 지갑의 `SETTLEMENT` 행 1건, 0원과 음수 정산은 금액 0). 검증 실패는 4xx `INVALID_AMOUNT`, 다시 보내도 실패 → [구매 확정 → 정산](../flows/confirm-settlement.md) |
| 인출 | 잔액을 계좌로. 신청 때 계좌 조회 · 암호화 저장, 즉시 차감, 같은 요청 한 번만. 계좌가 없으면 거절. 미수금이 있으면 인출 불가 |
| 미수금 | 음수 정산을 회수하지 못한 금액. 이후 충전 승인, 양수 정산금, 환불로 회원 지갑에 돈이 들어오면 min(미수금, 입금액)을 먼저 차감해 `REVENUE`로 (미수금 차감, `RECEIVABLE_COLLECTION`). 입금 행은 들어온 금액 그대로 남기고, 차감은 회원 지갑 OUT 행과 `REVENUE` IN 행으로 따로 기록(`ref_id` = 입금 거래 행 ID, 입금 1건당 한 번). 미수금이 없으면 `REVENUE`를 잠그지 않음, 있으면 회원 지갑 → (`HOLDING`) → `REVENUE` 순서로 잠금. 따로 갚는 API는 두지 않고, 직접 갚을 때는 충전 |
| 탈퇴 | 지갑 잔액, 진행 중 충전과 인출, 미수금, 미지급 정산이 있으면 탈퇴 불가. 잔액은 회원이 인출로 0을 만든 뒤 탈퇴 (탈퇴 전 잔액 정리 기능과 지갑 잠금은 두지 않음) |
| 대사 | 토스 거래와 내부 충전 기록을 매일 대조. 잔액을 늘리는 보정(토스 승인 · 내부 미승인)은 자동. ⬜ 잔액을 줄여야 하는 불일치 처리 → [미정](../open-questions.md) |

## 테스트 시나리오

S-D01 ~ S-D06 · S-G01 · S-G03 · S-G04 · S-G05 ~ S-G09 · S-P02 → [테스트 시나리오](../testing/scenarios.md)
