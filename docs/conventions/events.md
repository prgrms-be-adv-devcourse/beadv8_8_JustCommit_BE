# 이벤트 규칙

> 언제 읽나: Kafka 이벤트를 발행하거나 구독할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro 8 · 11-1 · 11-2, 노션 기획안 「이벤트 규칙」

## 이름과 토픽

| 규칙 | 내용 |
|---|---|
| 이벤트 이름 | 과거형 (`PurchaseConfirmed`, `ShipmentPickedUp`) |
| 토픽 | 도메인 기준 `{도메인}.events.v1` (예외: `member.address.events.v1` · `member.account.events.v1` · `product.review.v1` · `species.events.v1`) |
| Kafka Key | Aggregate ID — member: `memberId` · order/shipment: `sellerOrderId` · product: `productId` · species: `speciesId` · settlement: `sellerId` |
| 토픽 수 | 도메인 토픽 9개 + 각 재시도 토픽 · `-dlt` → [Kafka 토픽 표](../reference/kafka-topics.md) |

## 메시지 형식

본문은 `common-messaging`의 `EventEnvelope`(JSON)입니다. 받는 쪽은 Java 타입 정보 없이 `eventType` 문자열로 자기 클래스에 매핑합니다. 이벤트 record는 서비스마다 따로 둡니다 (공유 jar 없음).

```json
{
  "eventId": "7c1e...",
  "eventType": "PurchaseConfirmed",
  "version": 1,
  "occurredAt": "2026-11-12T10:00:00+09:00",
  "aggregateId": "1024",
  "payload": { "sellerOrderId": 1024, "sellerId": 7, "amount": 32000 }
}
```

| 위치 | 내용 |
|---|---|
| Key | Aggregate ID |
| 헤더 | `traceparent` (W3C, traceId 전파) |
| `occurredAt` | 시간대 포함 |
| `version` | payload 형식이 바뀌면 올림 |

## Outbox

알림용을 포함한 **모든 이벤트**를 Outbox로 발행합니다.

1. 업무 데이터와 `outbox_event` 행을 같은 트랜잭션에 저장
2. 커밋 직후 바로 Kafka로 발행 (`afterCommit`)
3. 발행에 실패해 남은 행은 재전송 스케줄러가 다시 보냄

```java
OutboxEvent e = outbox.save(PurchaseConfirmed.of(...));
afterCommit(() -> relay.publish(e.getId()));
```

| 칸 | 내용 |
|---|---|
| `id` | PK (= `eventId`) |
| `aggregate_type` · `aggregate_id` | Key 계산용 |
| `topic` · `event_type` | 보낼 토픽 · 이벤트 이름 |
| `payload` | 봉투 JSON |
| `traceparent` | 헤더로 보낼 값 |
| `status` · `published_at` · `retry_count` | 발행 상태 |
| `created_at` | 대기 시간 알림 기준 |

🟨 칸 이름 · 타입은 `common-messaging` 담당이 최종 확인합니다.

## 소비와 멱등

| 규칙 | 내용 |
|---|---|
| Consumer Group | 서비스 이름 (서비스마다 따로) |
| Inbox | 두지 않음 → [ADR 0002](../decisions/0002-outbox-all-events-no-inbox.md) |
| 멱등 | 받는 쪽이 **업무 키**로 중복을 막음: 정산 UK(sellerOrderId) · 거래 내역 UK · 알림 `eventKey` · 상품 상태 확인 · 복제본 · 벡터 upsert |
| `eventId` | 로그 · DLT 재처리 추적용 |

## 실패 처리

| 단계 | 내용 |
|---|---|
| 역직렬화 | `ErrorHandlingDeserializer` — 깨진 메시지가 소비자를 멈추지 않게 |
| 재시도 | `@RetryableTopic` 1 · 2 · 4초 (재시도 토픽으로 보냄) |
| 최종 실패 | `@DltHandler` → `{토픽}-dlt` + Slack 알림 |
| 재처리 | 수동. 방법은 ⬜ 미정 (🟨 제안: `failed_event` 표 + `POST /internal/v1/failed-events/{id}/replay`) |
| 순서 주의 | 재시도 토픽을 거치는 동안 같은 Key의 다음 메시지가 먼저 처리될 수 있음 → 받는 쪽은 `occurredAt` · 상태로 오래된 이벤트를 무시 |
