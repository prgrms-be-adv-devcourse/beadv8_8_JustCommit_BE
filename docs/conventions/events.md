# 이벤트 규칙

> 언제 읽나: Kafka 이벤트를 발행하거나 구독할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-10 · 관련: Miro 8 · 11-1 · 11-2, 노션 기획안 「이벤트 규칙」

## 이름과 토픽

| 규칙 | 내용 |
|---|---|
| 이벤트 이름 | 과거형 (`PurchaseConfirmed`, `ShipmentStatusChanged`) |
| 토픽 | 도메인 기준 `{도메인}.events.v1` (예외: `member.address.events.v1` · `product.review.v1` · `species.events.v1`). market → product는 이벤트마다 하나 `market.<이름>.v1` → [ADR 0016](../decisions/0016-product-sale-events-from-market.md) |
| Kafka Key | `aggregateId` — member: `memberId` · `market.events.v1`: `sellerOrderId` · `market.<이름>.v1`: `orderId` · product: `productId` · species: `speciesId` · settlement: `sellerId` |
| 토픽 수 | 도메인 토픽 7개 + market → product 토픽 5개 = **12개** → [Kafka 토픽 표](../reference/kafka-topics.md) |

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
| Key | `aggregateId` |
| 헤더 | `traceparent` (W3C, traceId 전파) |
| `occurredAt` | 시간대 포함 (`OffsetDateTime`) |
| `version` | payload 형식이 바뀌면 올림 |
| 개인정보 | 민감 필드만 토픽별 전송 키로 암호화 (AES-256-GCM, 예: 판매자 주소). 로그와 DLT 알림에는 payload를 남기지 않음 |

## Outbox

알림용을 포함한 **모든 이벤트**를 Outbox로 발행합니다.
이벤트를 보내는 서비스는 `common-messaging-outbox`를 의존하고 받기만 하는 서비스는 필요 없습니다.

1. 업무 데이터와 `outbox_event` 행을 같은 트랜잭션에 저장
2. 커밋 직후 바로 Kafka로 발행 (다른 스레드에서, Kafka가 저장했다고 응답해야 `PUBLISHED`)
3. 남은 행은 재전송 스케줄러가 **10초마다** 다시 보냄 (만든 지 5초 지난 행, 한 행씩 `FOR UPDATE SKIP LOCKED`). 커밋 직후 발행을 포함해 **5번** 실패하면 `FAILED` + 경고 로그 + 팀 메신저 알림
4. 발행이 끝난 행은 **7일** 보관 후 삭제 (1시간마다 정리, 모든 서비스 같은 값)

보낸 뒤 기록에 실패하면 같은 이벤트가 다시 나갈 수 있습니다(최소 1회 전달). 받는 쪽이 업무 키로 중복을 막습니다(아래 「소비와 멱등」).

```java
@Transactional
public void confirm(...) {
    // 업무 처리 ...
    outboxPublisher.publish(new PurchaseConfirmed(sellerOrderId, sellerId, amount)); // 트랜잭션 밖이면 예외
}
```

| 칸 | 타입 | 내용 |
|---|---|---|
| `id` | `uuid` | PK (= `eventId`) |
| `aggregate_id` | `varchar(100)` | Kafka Key |
| `topic` · `event_type` | `varchar(100)` | 보낼 토픽 · 이벤트 이름 |
| `payload` | `text` | 봉투 JSON |
| `traceparent` | `varchar(55)` | 헤더로 보낼 값 (common-logging 전까지 비어 있음) |
| `status` · `published_at` | `varchar(20)` · `timestamptz` | `PENDING` · `PUBLISHED` · `FAILED`, 발행 시각 |
| `attempt_count` · `last_error` | `integer` · `varchar(500)` | 실패 횟수(커밋 직후 발행 포함) · 마지막 실패 원인 |
| `created_at` | `timestamptz` | 재전송 · 대기 시간 알림 기준 |

인덱스 `(status, created_at)`. 테이블은 서비스마다 자기 Flyway 새 버전으로 만듭니다 (권장 SQL: `common-messaging-outbox`의 `outbox/outbox_event.sql`, 이미 적용된 V1은 고치지 않음). 재전송 · 정리를 끄려면 `outbox.scheduler.enabled=false`.

## 소비와 멱등

| 규칙 | 내용 |
|---|---|
| Consumer Group | 서비스 이름 (서비스마다 따로) |
| Inbox | 두지 않음 → [ADR 0002](../decisions/0002-outbox-all-events-no-inbox.md) |
| 멱등 | 받는 쪽이 **업무 키**로 중복을 막음 (UNIQUE · upsert): 정산 UK · 거래 내역 UK · 알림 `event_key` · 상품 `review_version` · 복제본 · 벡터 upsert |
| 오래된 이벤트 | `occurredAt`이 저장된 값보다 오래되면 무시 |
| `eventId` | 로그 · DLT 재처리 추적용 |

## 실패 처리

모든 리스너가 같은 방식(제자리 재시도)을 씁니다 → [ADR 0017](../decisions/0017-blocking-retry-dlt.md)

| 단계 | 내용 |
|---|---|
| 형식이 깨진 메시지 | `ErrorHandlingDeserializer` — 재시도 없이 바로 `원래토픽.DLT` |
| 처리 실패 재시도 | `DefaultErrorHandler` + BackOff 1 · 2 · 4초, 제자리(블로킹). 재시도 중 같은 파티션의 뒤 메시지는 기다림(최대 약 7초), 순서는 지켜짐. `@RetryableTopic`은 쓰지 않음 |
| 최종 실패 | `원래토픽.DLT`로 보내고 Slack 알림 (payload는 알림에 넣지 않음) |
| 재처리 | 수동 |
