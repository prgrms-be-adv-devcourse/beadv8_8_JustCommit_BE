# 이벤트 규칙

> 언제 읽나: Kafka 이벤트를 발행하거나 구독할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-12 · 관련: Miro 8 · 11-1 · 11-2, 노션 기획안 「이벤트 규칙」

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
| `version` | payload 형식이 바뀌면 올림 (1부터. 없거나 1 미만이면 형식 오류로 바로 DLT) |
| 개인정보 | 민감 필드만 토픽별 전송 키로 암호화 (AES-256-GCM, 예: 판매자 주소) → [민감 필드 암호화](#민감-필드-암호화). 로그와 DLT 알림에는 payload를 남기지 않음 |

### 민감 필드 암호화

`common-messaging`의 `EventFieldCipher`로 암호화 · 복호화합니다 (#371). 공통 도구는 문자열 암호화 · 복호화만 하고, 어떤 칸을 묶어 암호화할지(예: 주소 다섯 칸)는 각 서비스가 정합니다.

| 항목 | 규칙 |
|---|---|
| 방식 | AES-256-GCM, IV 12바이트를 `SecureRandom`으로 매번 새로 생성 (같은 키로 IV를 다시 쓰지 않음), 태그 128비트 |
| 형식 | `v1.{키 버전}:Base64(IV + 암호문 + 태그)` — `v1` 형식 버전, `k1` 같은 키 버전 |
| 접두사 인증 | 접두사(`v1.k1`)를 AAD로 넣어 형식 · 키 버전까지 위변조를 검증 |
| 키 | 토픽별 전송 키, Base64로 디코딩해 32바이트 (`openssl rand -base64 32`). 키 버전은 영문 소문자 · 숫자 |
| 암호화 · 복호화 | 암호화는 현재 키로만, 복호화는 접두사의 키 버전으로 키 목록에서 찾음 |
| 시작 시 검증 | 각 서비스가 `@Bean`으로 등록해 앱이 뜰 때 생성자에서 키 설정을 검증 (`@Lazy` 금지). 키 없음 · 길이 오류 · 풀리지 않은 `${...}`이면 앱이 뜨지 않음 |
| 실패 | 복호화 실패(모르는 형식 · 키 버전, 위변조, 잘린 값)는 `EventFieldCipherException` |
| 로그 | 예외 메시지 · 원인 예외에 평문 · 키 · 암호문을 넣지 않음 |

서비스 설정 예 (키 값은 환경변수):

```yaml
messaging:
  address-event-cipher:
    current-key: k1
    keys:
      k1: ${MEMBER_ADDRESS_EVENT_KEY}
```

**키 교체 절차**

1. 보내는 쪽 · 받는 쪽 모두 키 목록에 새 키(`k2`)를 추가한다 (현재 키는 아직 `k1`). 받는 쪽이 먼저 `k2`를 풀 수 있어야 한다.
2. 보내는 쪽의 현재 키를 `k2`로 바꾼다. 이후 새 이벤트는 `v1.k2:`.
3. 이전 키(`k1`)를 지우기 전에 `k1` 암호문이 남아 있지 않은지 확인한다. 받는 쪽 DB뿐 아니라 Kafka 토픽 · DLT · 아직 보내지 않은 Outbox 행처럼 다시 처리될 수 있는 곳도 포함한다. 값이 바뀌지 않으면 새 이벤트가 오지 않으므로 기다리는 것만으로는 끝나지 않을 수 있고, 다시 보내기나 재암호화를 따로 해야 한다.
4. 키가 유출돼서 바꾸는 경우에는 기존 데이터 재암호화를 별도로 계획한다.

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

모든 리스너가 같은 방식(제자리 재시도)을 씁니다 → [ADR 0017](../decisions/0017-blocking-retry-dlt.md). `common-messaging`이 기본 설정을 제공하고, 서비스가 에러 처리기를 직접 만들면 그걸 씁니다.

| 단계 | 내용 |
|---|---|
| 형식이 깨진 메시지 | `ErrorHandlingDeserializer` + `EventEnvelopeDeserializer` — 봉투로 못 바꾸면(JSON 오류 · 빈 칸 · `version` 1 미만) 재시도 없이 바로 `원래토픽.DLT` (받은 원문 그대로) |
| payload를 서비스 클래스로 못 바꿈 | Jackson 예외 — 다시 해도 같으므로 재시도 없이 바로 `원래토픽.DLT` |
| 처리 실패 재시도 | `DefaultErrorHandler` + BackOff 1 · 2 · 4초, 제자리(블로킹). 재시도 중 같은 파티션의 뒤 메시지는 기다림(최대 약 7초), 순서는 지켜짐. `@RetryableTopic`은 쓰지 않음 |
| 최종 실패 | `원래토픽.DLT`로 보내고 에러 로그 (토픽 · 위치 · `eventId` · 원인 종류만, payload 없음). Slack 알림은 로그 기반 알림을 만들 때 연결 |
| 재처리 | 수동 |

받는 서비스는 yml의 기존 `spring.kafka.consumer` 아래에 세 줄을 넣습니다.

```yaml
spring:
  kafka:
    consumer:
      group-id: product   # 서비스 이름
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      properties:
        spring.deserializer.value.delegate.class: com.justcommit.backend.common.messaging.consumer.EventEnvelopeDeserializer
```

리스너는 봉투를 받아 `eventType`으로 나눈 뒤 payload를 자기 record로 바꿉니다.

```java
@KafkaListener(topics = KafkaTopics.MEMBER_EVENTS)
void on(EventEnvelope envelope) {
    switch (envelope.eventType()) {
        case "MemberCreated", "MemberUpdated" -> upsert(envelopeMapper.readPayload(envelope, MemberChanged.class));
        case "MemberWithdrawn" -> withdraw(envelopeMapper.readPayload(envelope, MemberWithdrawn.class));
        default -> { } // 모르는 이벤트는 무시 (나중에 추가되는 이벤트 대비)
    }
}
```
