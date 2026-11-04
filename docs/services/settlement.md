# settlement 서비스

> 언제 읽나: settlement 서비스 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro 5, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 정산 | 구매 확정된 판매액에서 수수료(5%)를 떼고 정산 생성 → [구매 확정 → 정산 흐름](../flows/confirm-settlement.md) |
| 정산금 지급 | payment 내부 REST로 판매자 지갑에 지급 |
| 조회 | 판매자 정산 내역, 정산 완료 시각(market용) |

## 소유 테이블

| 테이블 | 설명 |
|---|---|
| `SETTLEMENT` | 정산 (sellerOrderId UK) |
| `MEMBER_REPLICA` | 회원 복제본 |
| `OUTBOX` | 발행 대기 이벤트 |

## 외부 API

`settlements` → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

| API | 부르는 서비스 | 상태 |
|---|---|---|
| `GET /internal/v1/settlements/seller-orders/{sellerOrderId}` | market | 🟨 제안 |

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
| `order.events.v1` `PurchaseConfirmed` · `OrderItemAutoConfirmed` | 정산 생성 → 지급 요청 | 정산 UK |
| `member.events.v1` | `MEMBER_REPLICA` 저장 · 갱신 | memberId upsert |

배송비(`ShipmentPickedUp`)는 settlement가 아니라 payment가 직접 받음

## 스케줄러

Outbox 재전송 → [스케줄러 표](../reference/schedulers.md)

## 정책

세미 프로젝트 정책 문서(`docs/policy.md`, 삭제됨)에서 옮긴 확정 정책입니다.

1. 정산 수수료율은 5%이다.
2. 수수료는 판매액에만 적용하며 배송비는 제외한다.
3. 수수료 계산에서 원 단위 미만은 버린다.
4. 구매는 배송 완료 시각부터 72시간이 지나면 자동으로 확정된다.
5. 판매자는 정산금으로 다른 상품을 구매할 수 있다.

## 테스트 시나리오

S-G01 · S-G04 → [테스트 시나리오](../testing/scenarios.md)
