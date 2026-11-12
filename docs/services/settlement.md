# settlement 서비스

> 언제 읽나: settlement 서비스 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-11 · 관련: Miro 5, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 정산 생성 | 판매자 주문이 끝나면(모든 상품이 구매 확정 또는 반품 완료) 판매자 주문 단위로 1번. 정산금 = 판매액 - 수수료 - 판매자 부담금 |
| 정산 지급 | 10초 큐가 `PENDING` 정산을 건별로 payment에 지급 · 회수 → [구매 확정 → 정산](../flows/confirm-settlement.md) |
| 조회 · 리포트 | 판매자 정산 내역, 수수료 · 배송비 · 정산금 리포트, 대사 |

## 소유 테이블

| 테이블 | 설명 |
|---|---|
| `SETTLEMENT` | 정산 (`sellerOrderId` UK, `PENDING` / `COMPLETED` / `FAILED`, 음수 가능, `shipping_fee` 배송비: 옛 토픽으로만 받은 정산은 null, 같은 판매자 주문이 새 토픽으로 오면 채움) |
| `MEMBER_REPLICA` | 회원 복제본 |
| `OUTBOX` | 발행 대기 이벤트 |

## 외부 API

`settlements` → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

| API | 부르는 서비스 | 상태 |
|---|---|---|
| `GET /internal/v1/settlements/seller-orders/{sellerOrderId}` | market | 🟨 제안 |
| ⬜ 탈퇴 확인 (`hasWithdrawalBlocker` + 사유 코드) | member | ✅ 기능 · ⬜ 경로 |

상세 → [내부 API 목록](../reference/internal-apis.md)

## 호출하는 내부 API

| 대상 | 용도 | 멱등 키 |
|---|---|---|
| payment | 정산금 지급 (`POST /internal/v1/settlement-payouts`) | settlementId |

## 발행 이벤트

| 토픽 | 이벤트 |
|---|---|
| `settlement.events.v1` | `SettlementCompleted` |

상세 → [Kafka 토픽 표](../reference/kafka-topics.md)

## 구독 이벤트

| 이벤트 | 받아서 하는 일 | 멱등 처리 |
|---|---|---|
| `market.events.v1` `PurchaseConfirmed` | 판매자 주문이 끝나면(수동 · 자동 확정 모두) 정산 생성. `shippingFee`로 배송비도 정산 때 옮김 | 정산 UK (`uk_settlement_seller_order_id`) |
| `member.events.v1` | `MEMBER_REPLICA` 저장 · 갱신 | memberId upsert |
| `market.purchase-confirmed.v1` (전환 중) | 옛 형식 구매 확정으로 정산 생성 (`shipping_fee` null). market 전환이 끝나면 삭제 | 정산 UK |

## 스케줄러

정산 지급 큐(10초, 끝까지) · Outbox 재전송 → [스케줄러 표](../reference/schedulers.md)

## 정책

노션 「정책 정리」 5절 기준입니다.

| 주제 | 규칙 |
|---|---|
| 수수료 | 5%, 판매액에만 (배송비 제외), 원 단위 미만 버림 |
| 생성 | 판매자 주문이 끝나면 1번. 반품 진행 중이면 만들지 않음, 모두 수거 전 취소면 만들지 않음, 모두 반품이면 부담금만큼 음수 정산 |
| 상태 | `PENDING` · `COMPLETED` · `FAILED` (`HOLD` 없음), 실패하면 재시도 |
| 지급 | 건별, `settlementId` 멱등. 0 이상이면 판매자 인출 가능 금액으로, 음수면 판매자 정산금 → 충전금 순으로 회수, 못 받은 금액은 미수금 |
| 돈의 이동 | 정산금 → 판매자 지갑, 수수료 · 배송비 · 판매자 부담금 → `REVENUE` |
| 탈퇴 | 미지급 정산(대기 · 실패)이 있으면 불가. 미수금은 payment가 확인 |
| 대사 | 구매 확정된 판매자 주문 ↔ 정산 기록, 완료 정산 ↔ 판매자 지갑 입금 |

## 테스트 시나리오

S-G01 · S-G04 · S-G05 · S-H02 → [테스트 시나리오](../testing/scenarios.md)
