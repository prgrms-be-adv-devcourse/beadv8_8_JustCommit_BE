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

- Payment waiting TTL key: `order:payment:ttl:{orderId}`
- Refresh token key: `auth:refresh:{memberId}:{tokenId}`
- TODO: implement RedisTemplate use cases only with their owning domain module.
- TODO: implement TTL expiration listeners only when the order-expiration workflow is specified.

## Kafka

- TODO candidates: `PaymentCompleted`, `PaymentFailed`, `OrderExpired`, `PurchaseConfirmed`, `RefundCompleted`, `ShipmentDelivered`.
- TODO: add topics, producers, consumers, DLQ, retry policy, and Outbox only in dedicated follow-up work.
- Do not implement a duplicate Spring Event flow. Future asynchronous domain-event handling is Kafka-centered.
