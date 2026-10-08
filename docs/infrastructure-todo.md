# Infrastructure conventions and deferred work

## Module boundaries

- Do not access another module's Repository directly.
- Do not reference another module's JPA Entity directly.
- Cross-module communication must use a public API, Port, or DTO.
- Foreign IDs may be stored, but JPA entity relationships must not cross module boundaries.
- Keep module boundaries suitable for a future MSA split.
- `common` is for cross-cutting infrastructure only; do not concentrate business logic there.
- `market` owns cart, checkout, order, shipment, and refund as one transaction-lifecycle module.
- `market` may use only the public API, Port, or DTO of `member`, `product`, `payment`, and `settlement`.

## Redis

Production Redis runs in Docker on EC2 with `maxmemory-policy noeviction`, so every key must have a TTL.

Rules:
- Key format: `domain:purpose:identifier` (lowercase, colon-separated).
- Every key must have a TTL.
- Normalize emails to lowercase before building keys.
- Never store raw tokens; store a hash.

| Key | Value | TTL |
| --- | --- | --- |
| `auth:email:{email}` | verification code | 5 min |
| `auth:email:verified:{email}` | `true` | 30 min (deleted on sign-up) |
| `auth:email:cooldown:{email}` | `1` | 60 s |
| `auth:refresh:{tokenHash}` | memberId | 1 day (same as refresh cookie Max-Age) |
| `auth:signup:{token}` | provider, social ID, email | 30 min (deleted when sign-up completes) |
| `idem:{api}:{memberId}:{key}` | `PROCESSING` / result | 30 s while processing, 24 h when done |
| `order:payment:ttl:{orderId}` | orderId | 30 min (payment wait time) |

Undecided: where to store the OAuth2 `state` for Naver and Kakao login (cookie vs `auth:oauth2:state:{state}`, 5 min). The member owner is investigating.

- TODO: implement RedisTemplate use cases only with their owning domain module.
- TODO: implement TTL expiration listeners only when the order-expiration workflow is specified.

## Kafka

- TODO candidates: `PaymentCompleted`, `PaymentFailed`, `OrderExpired`, `PurchaseConfirmed`, `RefundCompleted`, `ShipmentDelivered`.
- TODO: add topics, producers, consumers, DLQ, retry policy, and Outbox only in dedicated follow-up work.
- Do not implement a duplicate Spring Event flow. Future asynchronous domain-event handling is Kafka-centered.
