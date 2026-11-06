# payment 서비스

> 언제 읽나: payment 서비스 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-06 · 관련: Miro 5 · 13, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 지갑 | 회원 지갑 1개 = 충전금 + 인출 가능 금액(정산금) + 미수금. 시스템 지갑 `HOLDING` · `REVENUE`는 `wallet_type`으로 직접 소유 |
| 충전 · 충전 취소 | 토스페이먼츠, 충전 건당 취소 1회 |
| 결제 · 환불 | market의 내부 REST로 잔액 차감 · 상품 금액 · 배송비 환불 |
| 정산 지급 | settlement의 내부 REST로 지급(양수) 또는 회수(음수) |
| 인출 | 인출 가능 금액을 member에서 조회한 계좌로 |
| 대사 | 토스 거래와 내부 충전 기록 매일 대조 |

## 소유 테이블

| 테이블 | 설명 |
|---|---|
| `WALLET` | 지갑 (`wallet_type`: `MEMBER` / `HOLDING` / `REVENUE`, `charge_balance` · `withdrawable_balance` · 미수금) |
| `WALLET_TRANSACTION` | 거래 내역 (거래 유형 + 대상 ID UK) |
| `CHARGE` | 충전 (`PENDING` · `APPROVED` · `CANCEL_PENDING` 등) |
| `CASH_OUT` | 인출 (계좌 암호화 저장) |
| `MEMBER_REPLICA` | 회원 복제본 |
| `OUTBOX` | ⬜ 발행하는 이벤트가 없어 필요 여부 확인 |

## 외부 API

`wallet` · `wallet/transactions` · `wallet/charges` · `wallet/charges/{id}/confirm` + ⬜ 충전 취소 · 인출 · 탈퇴 잔액 정리 → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

| API | 부르는 서비스 | 멱등 키 |
|---|---|---|
| `POST /internal/v1/payments` | market | orderId |
| `POST /internal/v1/refunds/order-items/{orderItemId}` | market | orderItemId |
| `POST /internal/v1/refunds/shipping-fees/{sellerOrderId}` | market | sellerOrderId |
| `POST /internal/v1/settlement-payouts` | settlement | settlementId |
| ⬜ 탈퇴 확인 | member | – |

상세 → [내부 API 목록](../reference/internal-apis.md)

## 호출하는 내부 API

| 대상 | 용도 |
|---|---|
| member | ⬜ 계좌 조회 (인출 신청 때) |
| 토스페이먼츠 | 충전 승인 · 취소 · 조회 (연결 3초 · 응답 65초) |

## 발행 이벤트

없음 (출금 · 충전 알림을 만들지 않음)

## 구독 이벤트

| 이벤트 | 받아서 하는 일 | 멱등 처리 |
|---|---|---|
| `member.events.v1` | `MEMBER_REPLICA` 저장 · 갱신, `MemberCreated`이면 지갑 생성 (없으면 필요한 시점에 생성) | memberId upsert, 지갑 1개 |

배송비 매출은 이벤트가 아니라 정산 지급 때 옮깁니다. 계좌 이벤트는 없습니다.

## 스케줄러

토스 결과 불명 조회(3회 → `REVIEW_REQUIRED`) · 토스 대사(하루 1번) → [스케줄러 표](../reference/schedulers.md)

## 정책

노션 「정책 정리」 4절 기준입니다.

| 주제 | 규칙 |
|---|---|
| 조회 | 예치금 · 거래 내역 조회는 지갑을 만들지 않음 (없으면 잔액 0 · 빈 목록) |
| 충전 | 10,000원 단위, 최소 10,000원. 승인되면 충전금 증가. 결과를 모르면 `PENDING`("충전 처리 중", 예치금 미반영) → 조회로 확정 (승인은 결제 인증 후 10분 안) |
| 충전 취소 | 충전 건당 1회. 금액 = min(충전 금액, 남은 충전금), 0원이면 불가, 원래 결제수단으로. 충전금을 먼저 차감하고 `CANCEL_PENDING` → 토스 거절이면 복원 · 기회 유지. `PENDING` · 확인 중 충전은 취소 불가 |
| 결제 | 주문 금액만큼 차감해 `HOLDING`으로, 같은 주문은 한 번만 |
| 수거 전 취소 환불 | 취소된 상품 금액을 `HOLDING` → 구매자 지갑, 전부 취소면 배송비도. 각각 한 번만 |
| 반품 환불 | 반품 택배가 판매자에게 도착하면 즉시. 결제 때 쓴 잔액대로 충전금부터 돌려줌 |
| 인출 | 인출 가능 금액만 계좌로 (충전금은 탈퇴 정리 때만). 신청 때 계좌 조회 · 암호화 저장, 즉시 차감 · 실패하면 복원, 같은 요청 한 번만. 계좌가 없으면 거절. 미수금만큼은 인출 불가 |
| 미수금 | 음수 정산을 회수하지 못한 금액. 이후 정산금 · 충전 · 환불로 들어오는 돈에서 먼저 차감해 `REVENUE`로 |
| 탈퇴 잔액 정리 | 잔액 · 진행 중 충전 · 충전 취소 · 인출 · 미수금이 있으면 탈퇴 불가. 회원이 직접 정리(토스 취소 기한 안 = 충전 취소, 나머지 = 계좌 인출), 정리를 시작하면 충전 · 결제 차단 (⬜ 잠금 방식) |
| 대사 | 잔액을 늘리는 보정은 자동, 줄여야 하는 불일치는 기록 · 팀 알림만 |

## 테스트 시나리오

S-D01 ~ S-D06 · S-G01 · S-G04 · S-G05 · S-P01 · S-P02 → [테스트 시나리오](../testing/scenarios.md)
