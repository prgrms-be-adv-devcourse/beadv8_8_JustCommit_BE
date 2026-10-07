# Module architecture

## Spring Modulith modules

| Module | Owns | Boundary rule |
| --- | --- | --- |
| `member` | Member identity, profiles, and delivery addresses | Exposes member and delivery-address lookup APIs only. |
| `product` | Product posts, current price, seller, and sale availability | The market module asks it to query or reserve/release a post; its entities and repositories remain internal. |
| `market` | Cart, checkout, orders, seller orders, ordered items, shipments, and refunds | Owns the marketplace transaction lifecycle as one module. |
| `payment` | Payment attempts, approval, cancellation, and refund execution | Receives payment/refund requests through its public API; does not mutate market entities. |
| `settlement` | Seller settlement after purchase confirmation | Starts from a market event or public API, not direct order-table access. |
| `notification` | Customer and seller notifications | Consumes explicit application events or public DTOs only. |
| `common` | Cross-cutting configuration, security, error handling, and shared technical utilities | Must not contain business-domain logic. |

## Internal structure of `market`

`market` is intentionally one Spring Modulith module. Its child packages are implementation
responsibilities, not independent application modules.

| Package | Responsibility |
| --- | --- |
| `market.cart` | `Cart` and `CartItem`; records what a member intends to buy. It never reserves a product. |
| `market.checkout` | Direct/cart checkout; validates selected posts, snapshots delivery details, creates `Orders`, `SellerOrder`, and `OrderItem`, and requests product reservation before payment. |
| `market.order` | The post-checkout lifecycle of `Orders`, `SellerOrder`, and `OrderItem`: lookup, cancellation eligibility, and purchase confirmation. |
| `market.shipment` | `Shipment` creation, tracking number, and delivery-state changes per seller order. |
| `market.refund` | `OrderRefund` request/decision workflow; coordinates ordered-item state and the payment module's refund API. |

## Why shipment and refund stay inside `market`

Both responsibilities continuously apply order policy. Shipment is keyed by `SellerOrder`, and a
refund changes an `OrderItem` state while requiring payment execution. Making either a top-level
module now would create tight cross-module writes and event/API cycles without providing an
independent business boundary.

Different developers can own `market.shipment` and `market.refund`, but they should coordinate
through market application services rather than access each other's repositories directly. Split
them into separate Spring Modulith modules only when their workflows, persistence, release cycle,
and APIs become independently stable.

## Cross-module direction

```text
market -> member      delivery-address and member lookup
market -> product     saleability query and post reservation/release
market -> payment     payment approval, cancellation, and refund execution
market -> settlement  purchase-confirmed notification/event
market -> notification explicit notification event
```

Store foreign IDs across these boundaries when necessary. Do not create cross-module JPA entity
associations or access another module's repositories directly.
