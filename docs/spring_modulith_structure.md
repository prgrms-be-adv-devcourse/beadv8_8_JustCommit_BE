# Spring Modulith 폴더 구조 가이드

> 팀원이 프로젝트 구조를 처음 봤을 때 **어디에 어떤 코드를 넣어야 하는지 바로 판단할 수 있도록** 만든 가이드입니다.

---

# 0. 30초 요약

우리 프로젝트의 큰 구조는 아래와 같습니다.

```text
src/main/java/com/plantmarket
│
├─ PlantMarketApplication.java
├─ common
├─ member
├─ product
├─ market
│  ├─ cart
│  ├─ order
│  ├─ shipment
│  └─ refund
├─ payment
├─ settlement
└─ notification
```

Spring Modulith가 실제 모듈로 보는 단위는 다음입니다.

```text
member
product
market
payment
settlement
notification
```

`market/cart`, `market/order`, `market/shipment`, `market/refund`는 별도 Modulith 모듈이 아니라 **Market 모듈 내부 기능 패키지**입니다.

각 모듈은 기본적으로 아래 형태를 사용합니다.

```text
payment
├─ PaymentUseCase.java      ← 다른 모듈에 공개하는 명령 인터페이스
├─ PaymentQuery.java        ← 다른 모듈에 공개하는 조회 인터페이스
├─ PaymentResult.java       ← 공개 DTO
├─ package-info.java
│
├─ application             ← 업무 흐름 / 트랜잭션
├─ domain                  ← Entity / Enum / 도메인 규칙
├─ infrastructure          ← Repository / Redis / Kafka / 외부 API
└─ presentation            ← Controller / HTTP DTO
```

## 핵심 규칙 5개

1. **다른 모듈의 Repository를 직접 import하지 않는다.**
2. **다른 모듈의 Entity를 직접 import하지 않는다.**
3. **모듈 간 호출은 루트의 `UseCase`, `Query`, 공개 DTO를 사용한다.**
4. `application / domain / infrastructure / presentation`은 해당 모듈의 내부 구현이다.
5. 새 클래스를 만들기 전에 먼저 **“이 클래스는 어느 도메인의 책임인가?”** 를 판단한다.

예를 들어 Market에서 결제를 호출해야 한다면:

```text
잘못된 방식

Market
  ↓
PaymentRepository
```

```text
권장 방식

Market
  ↓
PaymentUseCase
  ↓
PaymentService
  ↓
PaymentRepository
```

```java
@Service
@RequiredArgsConstructor
class CheckoutService {

    private final ProductReservationUseCase productReservationUseCase;
    private final PaymentUseCase paymentUseCase;
}
```

여기까지만 이해해도 기본 개발은 시작할 수 있습니다.

---

# 1. 왜 Spring Modulith를 사용하는가?

우리 프로젝트는 **Spring Modulith 기반 Modular Monolith** 구조를 사용합니다.

현재 세미 프로젝트에서는 하나의 Spring Boot 애플리케이션과 하나의 PostgreSQL을 사용하지만,
코드에서는 아래 영역을 서로 독립적인 도메인 모듈처럼 관리합니다.

```text
Member
Product
Market
Payment
Settlement
Notification
```

핵심은 다음입니다.

> **같은 프로젝트 안에 있지만, 서로 다른 서비스처럼 경계를 지킨다.**

다른 모듈의 내부 구현에 직접 의존하지 않도록 만들어,
추후 특정 영역을 MSA로 분리할 때 변경 범위를 줄이는 것이 목적입니다.

---

# 2. Spring Modulith에서 모듈은 어떻게 구분되는가?

기본적으로 `@SpringBootApplication`이 위치한 Base Package 바로 아래 패키지를
하나의 Application Module로 봅니다.

```text
com.plantmarket
├─ PlantMarketApplication.java
├─ member
├─ product
├─ market
├─ payment
├─ settlement
└─ notification
```

따라서 Modulith 관점에서는:

```text
member       = Application Module
product      = Application Module
market       = Application Module
payment      = Application Module
settlement   = Application Module
notification = Application Module
```

입니다.

Gradle Multi Module 구조가 아니므로 기능마다 별도 `build.gradle`을 만드는 방식은 사용하지 않습니다.

```text
X member/build.gradle
X product/build.gradle
X payment/build.gradle
```

우리 프로젝트는 **하나의 Spring Boot 프로젝트 + 패키지 기반 모듈 분리** 방식입니다.

---

# 3. 왜 Cart / Order / Shipment / Refund를 Market으로 묶는가?

현재 거래 흐름은 다음처럼 연결됩니다.

```text
Cart
  ↓
Checkout
  ↓
Order
  ↓
SellerOrder
  ├─ OrderItem
  └─ Shipment

OrderItem
  ↓
Refund
```

역할은 다음과 같습니다.

```text
Cart        → 구매 시작점
Order       → 전체 주문 상태
SellerOrder → 판매자별 주문 묶음
OrderItem   → 실제 구매 상품 단위
Shipment    → SellerOrder 기준 배송
Refund      → OrderItem 기준 환불
```

이들을 처음부터 모두 별도 Modulith 모듈로 만들면 같은 거래 흐름 안에서도
Public API와 Port가 지나치게 많이 생깁니다.

그래서 세미 프로젝트에서는:

```text
market
├─ cart
├─ order
├─ shipment
└─ refund
```

로 묶고, 필요해질 때 독립 모듈 또는 MSA 서비스로 승격하는 방향을 사용합니다.

---

# 4. 전체 폴더 구조 예시

```text
src/main/java/com/plantmarket
│
├─ PlantMarketApplication.java
│
├─ common
│  ├─ entity
│  │  └─ BaseTimeEntity.java
│  ├─ exception
│  │  ├─ BusinessException.java
│  │  └─ GlobalExceptionHandler.java
│  └─ response
│     └─ ApiResponse.java
│
├─ member
│  ├─ MemberUseCase.java
│  ├─ MemberQuery.java
│  ├─ MemberInfo.java
│  ├─ package-info.java
│  ├─ application
│  ├─ domain
│  ├─ infrastructure
│  └─ presentation
│
├─ product
│  ├─ ProductQuery.java
│  ├─ ProductReservationUseCase.java
│  ├─ ProductInfo.java
│  ├─ package-info.java
│  ├─ application
│  ├─ domain
│  ├─ infrastructure
│  └─ presentation
│
├─ market
│  ├─ CheckoutUseCase.java
│  ├─ OrderQuery.java
│  ├─ package-info.java
│  │
│  ├─ cart
│  │  ├─ application
│  │  ├─ domain
│  │  ├─ infrastructure
│  │  └─ presentation
│  │
│  ├─ order
│  │  ├─ application
│  │  │  ├─ CheckoutService.java
│  │  │  ├─ OrderQueryService.java
│  │  │  ├─ OrderCancelService.java
│  │  │  └─ PurchaseConfirmService.java
│  │  ├─ domain
│  │  │  ├─ Order.java
│  │  │  ├─ SellerOrder.java
│  │  │  ├─ OrderItem.java
│  │  │  ├─ OrderStatus.java
│  │  │  └─ OrderItemStatus.java
│  │  ├─ infrastructure
│  │  │  ├─ OrderRepository.java
│  │  │  ├─ SellerOrderRepository.java
│  │  │  └─ OrderItemRepository.java
│  │  └─ presentation
│  │     ├─ OrderController.java
│  │     └─ dto
│  │
│  ├─ shipment
│  │  ├─ application
│  │  ├─ domain
│  │  ├─ infrastructure
│  │  └─ presentation
│  │
│  └─ refund
│     ├─ application
│     ├─ domain
│     ├─ infrastructure
│     └─ presentation
│
├─ payment
│  ├─ PaymentUseCase.java
│  ├─ PaymentQuery.java
│  ├─ PaymentResult.java
│  ├─ package-info.java
│  ├─ application
│  ├─ domain
│  ├─ infrastructure
│  └─ presentation
│
├─ settlement
│  ├─ SettlementUseCase.java
│  ├─ package-info.java
│  ├─ application
│  ├─ domain
│  ├─ infrastructure
│  └─ presentation
│
└─ notification
   ├─ NotificationUseCase.java
   ├─ package-info.java
   ├─ application
   ├─ domain
   ├─ infrastructure
   └─ presentation
```

---

# 5. 모듈 루트는 Public API 영역

예를 들어 Payment 모듈:

```text
payment
├─ PaymentUseCase.java
├─ PaymentQuery.java
├─ PaymentResult.java
├─ package-info.java
├─ application
├─ domain
├─ infrastructure
└─ presentation
```

외부 모듈에서 사용할 수 있는 공개 계약은:

```text
PaymentUseCase
PaymentQuery
PaymentResult
```

입니다.

반면 아래는 Payment 모듈의 내부 구현입니다.

```text
payment.application.PaymentService
payment.domain.Payment
payment.infrastructure.PaymentRepository
```

다른 모듈은 내부 구현을 직접 참조하지 않습니다.

---

# 6. UseCase / Query는 무엇인가?

`UseCase`는 다른 모듈이 해당 모듈의 **상태 변경 기능**을 호출하기 위한 공개 계약입니다.

```java
package com.plantmarket.payment;

public interface PaymentUseCase {

    PaymentResult pay(Long orderId, long amount);
}
```

구현체는 내부에 둡니다.

```java
package com.plantmarket.payment.application;

@Service
class PaymentService implements PaymentUseCase {

    @Override
    public PaymentResult pay(Long orderId, long amount) {
        // 실제 결제 처리
        return PaymentResult.success(orderId);
    }
}
```

외부 모듈은 구현체가 아니라 다음처럼 인터페이스에 의존합니다.

```java
private final PaymentUseCase paymentUseCase;
```

`Query`는 조회 기능을 공개할 때 사용합니다.

```java
public interface PaymentQuery {

    PaymentInfo getPayment(Long orderId);
}
```

정리:

```text
UseCase → 상태 변경 / 명령
Query   → 조회
```

---

# 7. 각 내부 패키지의 역할

## application

업무 흐름과 트랜잭션을 조율합니다.

```text
CheckoutService
OrderCancelService
PurchaseConfirmService
PaymentService
```

주요 책임:

```text
트랜잭션 관리
도메인 객체 호출
자기 모듈 Repository 호출
다른 모듈 Public API 호출
업무 흐름 조율
```

## domain

도메인의 핵심 상태와 규칙을 둡니다.

```text
Order
OrderItem
SellerOrder
Post
Payment
Shipment

OrderStatus
PostStatus
ShipmentStatus
```

가능하면 상태 변경 규칙을 Domain 객체가 직접 가지도록 합니다.

```java
public void completePayment() {
    if (status != OrderStatus.PAYMENT_PENDING) {
        throw new IllegalStateException();
    }

    status = OrderStatus.PAID;
}
```

## infrastructure

DB, Redis, Kafka, 외부 API처럼 기술적인 구현을 둡니다.

```text
Repository
RedisRepository
KafkaProducer
PG Client
택배 API Client
```

## presentation

외부 HTTP 요청/응답을 담당합니다.

```text
Controller
Request DTO
Response DTO
```

기본 호출 방향은:

```text
Controller
   ↓
Application Service
   ↓
Domain
```

입니다.



# 8. 같은 Market 내부에서는 어떻게 호출하는가?

아래 패키지는 모두 같은 `market` 모듈 내부입니다.

```text
market
├─ cart
├─ order
├─ shipment
└─ refund
```

따라서 Spring Modulith 기준으로는:

```text
market.order
→ market.shipment
```

호출을 위해 반드시 별도 공개 UseCase를 만들 필요는 없습니다.

다만 내부 기능 간 결합이 지나치게 커지면 나중에 별도 모듈로 분리하기 어려워질 수 있으므로,
필요 이상의 직접 의존은 피합니다.

---

# 9. 다른 모듈 호출은 Public API를 사용한다

예를 들어 Market에서 Product를 사용한다고 가정합니다.

잘못된 구조:

```text
Market
  ↓
ProductRepository
```

```java
private final PostRepository postRepository;
```

권장 구조:

```text
Market
  ↓
ProductQuery
  ↓
Product 내부 구현
```

예:

```java
public interface ProductQuery {

    ProductInfo getProduct(Long postId);
}
```

Market:

```java
@Service
@RequiredArgsConstructor
class CheckoutService {

    private final ProductQuery productQuery;
}
```

핵심은 다음입니다.

> 다른 모듈의 기능이 필요할 때 Repository를 가져오는 것이 아니라, 그 모듈이 제공해야 할 공개 계약을 생각한다.

---

# 10. 다른 모듈 Entity 직접 참조 금지

잘못된 예:

```java
public Post getPost(Long postId);
```

이렇게 하면 Market이 Product 모듈의 `Post` Entity를 직접 알게 됩니다.

권장:

```java
public ProductInfo getProduct(Long postId);
```

예:

```java
public record ProductInfo(
        Long postId,
        Long sellerId,
        String title,
        long price,
        String status
) {
}
```

정리:

```text
Product Entity 직접 사용   X
ProductInfo DTO 사용       O
```

---

# 11. 다른 모듈 Repository 직접 참조 금지

잘못된 예:

```java
@Service
class CheckoutService {

    private final PostRepository postRepository;
    private final PaymentRepository paymentRepository;
}
```

권장:

```java
@Service
class CheckoutService {

    private final ProductReservationUseCase productReservationUseCase;
    private final PaymentUseCase paymentUseCase;
}
```

이렇게 해야 Product나 Payment가 나중에 별도 서비스로 분리되어도 변경 범위가 줄어듭니다.

---

# 12. 모듈 간 JPA Entity 연관관계도 최소화한다

다른 모듈 Entity와 직접 연관관계를 맺지 않습니다.

피해야 하는 예:

```java
@ManyToOne
private Member buyer;
```

권장:

```java
private Long buyerId;
```

즉 DB에는 `buyer_id`를 저장하더라도 코드에서는:

```text
Order Entity
   ↓ JPA Relation
Member Entity
```

같은 모듈 간 Entity 결합을 피합니다.

---

# 13. package-info.java

각 최상위 모듈에는 `package-info.java`를 둡니다.

Payment:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Payment"
)
package com.plantmarket.payment;
```

Market:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Market"
)
package com.plantmarket.market;
```

Product:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Product"
)
package com.plantmarket.product;
```

초기에는 단순히 모듈 선언만 사용하고,
필요하면 이후 `allowedDependencies`를 추가합니다.

---

# 14. internal 폴더를 따로 만들지 않는 이유

이전 교육용 예제에서는:

```text
payment
├─ PaymentUseCase
└─ internal
   └─ PaymentService
```

처럼 단순하게 구성했습니다.

실제 프로젝트에서는 파일이 많아지기 때문에:

```text
payment
├─ PaymentUseCase
├─ PaymentQuery
├─ application
├─ domain
├─ infrastructure
└─ presentation
```

처럼 역할별로 나눕니다.

Spring Modulith 기준으로 `application`, `domain`, `infrastructure`, `presentation`은
이미 모듈 내부 구현입니다.

따라서 아래처럼 `internal`을 한 단계 더 만들 필요가 없습니다.

```text
payment/internal/application
payment/internal/domain
```

---

# 15. common 사용 규칙

`common`에는 기술적으로 공통적인 요소만 둡니다.

가능:

```text
BaseTimeEntity
ApiResponse
GlobalExceptionHandler
공통 Exception
공통 기술 Util
```

피해야 하는 것:

```text
MemberUtil
OrderUtil
PaymentUtil
ProductValidator
SettlementService
```

비즈니스 로직을 common에 넣기 시작하면
모든 모듈이 common을 통해 다시 강하게 연결됩니다.

원칙:

> **비즈니스 규칙은 해당 도메인 모듈이 소유한다.**

---

# 16. Event 위치

다른 모듈이 소비해야 하는 이벤트는
외부에서 사용할 수 있는 위치에 둡니다.

예:

```text
market
├─ PurchaseConfirmedEvent.java
├─ OrderExpiredEvent.java
└─ ...
```

예:

```java
public record PurchaseConfirmedEvent(
        Long orderId,
        Long orderItemId,
        Long sellerId,
        long amount
) {
}
```

이벤트가 많아지면 추후 별도의 공개 패키지나 Named Interface 전략을 적용할 수 있습니다.
초기에는 구조를 복잡하게 만들지 않습니다.

---

# 17. Market 내부 역할

```text
market
├─ cart
├─ order
├─ shipment
└─ refund
```

| 영역 | 책임 |
|---|---|
| cart | 장바구니 담기 / 삭제 / 조회 |
| order | Checkout / Order / SellerOrder / OrderItem / 주문 취소 / 구매확정 |
| shipment | 판매자 발송 / 송장 / 배송 상태 |
| refund | 환불 요청 / 승인 / 환불 상태 |

핵심:

```text
Market
= 큰 모듈 경계

Cart / Order / Shipment / Refund
= Market 내부 기능 경계
```

---

# 18. 팀원별 작업 예시

담당자가 다르다는 이유로 Modulith 모듈을 나누지 않습니다.

예:

```text
팀원 A
→ member

팀원 B
→ product

팀원 C
→ market.cart
→ market.order

팀원 D
→ market.shipment
→ market.refund

팀원 E
→ payment
→ settlement
```

즉 모듈 경계는:

```text
사람 기준 X
도메인 결합도 기준 O
```

으로 정합니다.

---

# 19. 의존 방향 예시

```text
Member
   ↑
   │ Public API
   │
Market
   │
   ├──── Product Public API
   │
   ├──── Payment Public API
   │
   └──── Kafka Event
              ↓
         Settlement
```

Checkout 예:

```text
Market.CheckoutService
        │
        ├─ MemberQuery
        │    └─ 배송지 조회
        │
        ├─ ProductReservationUseCase
        │    └─ 상품 선점
        │
        └─ PaymentUseCase
             └─ 결제
```

Market은 아래 Repository를 직접 사용하지 않습니다.

```text
MemberRepository
PostRepository
PaymentRepository
```

---

# 20. 잘못된 예 / 권장 예

## 잘못된 예

```java
@Service
class CheckoutService {

    private final MemberRepository memberRepository;
    private final PostRepository postRepository;
    private final PaymentRepository paymentRepository;
}
```

문제:

```text
Market이
Member DB 구조를 알고 있음
Product DB 구조를 알고 있음
Payment DB 구조를 알고 있음
```

모듈 경계가 사라집니다.

## 권장 예

```java
@Service
@RequiredArgsConstructor
class CheckoutService {

    private final MemberQuery memberQuery;
    private final ProductReservationUseCase productReservationUseCase;
    private final PaymentUseCase paymentUseCase;
}
```

각 모듈은 자기 Repository와 Entity만 직접 관리합니다.

---

# 21. Controller 위치

Controller는 해당 기능 내부 `presentation`에 둡니다.

예:

```text
market
└─ cart
   └─ presentation
      ├─ CartController.java
      └─ dto
         ├─ CartAddRequest.java
         └─ CartResponse.java
```

Order:

```text
market
└─ order
   └─ presentation
      ├─ OrderController.java
      └─ dto
```

모듈 간 통신 DTO와 HTTP 응답 DTO는 목적이 다르므로 가능하면 구분합니다.

```text
ProductInfo
= 모듈 간 통신 DTO

ProductResponse
= 프론트엔드 HTTP 응답 DTO
```

---

# 22. Repository 위치

Repository는 해당 도메인의 `infrastructure`에 둡니다.

예:

```text
market
└─ order
   └─ infrastructure
      ├─ OrderRepository.java
      ├─ OrderItemRepository.java
      └─ SellerOrderRepository.java
```

Product:

```text
product
└─ infrastructure
   ├─ PostRepository.java
   └─ SpeciesRepository.java
```

다른 모듈에서 이 Repository를 import하면 안 됩니다.

---

# 23. Modulith 구조 검증

Spring Modulith에서 모듈 경계 위반을 테스트할 수 있습니다.

```java
class ModulithArchitectureTest {

    @Test
    void verifyModules() {

        ApplicationModules
                .of(PlantMarketApplication.class)
                .verify();
    }
}
```

이 테스트를 통해 다음을 검증합니다.

```text
다른 모듈 내부 Package 직접 참조
모듈 간 순환 의존성
잘못된 모듈 의존 관계
```

PR 전에 해당 테스트가 성공하는지 확인합니다.

---

# 24. 새 클래스를 만들 때 위치 판단 방법

새 파일을 만들 때 아래 순서로 판단합니다.

## 1) 어느 도메인의 기능인가?

```text
상품
→ product

회원
→ member

장바구니 / 주문 / 배송 / 환불
→ market

결제
→ payment

정산
→ settlement
```

## 2) Market 안이라면 어느 기능인가?

```text
장바구니
→ market.cart

주문
→ market.order

배송
→ market.shipment

환불
→ market.refund
```

## 3) 역할은 무엇인가?

```text
Controller / Request / Response
→ presentation

업무 흐름 / Transaction
→ application

Entity / Enum / 도메인 규칙
→ domain

Repository / Redis / Kafka / 외부 API
→ infrastructure
```

## 4) 다른 모듈에서 사용해야 하는가?

YES:

```text
모듈 Root에 UseCase / Query / DTO 공개
```

NO:

```text
해당 모듈 내부 패키지에 둔다.
```

---

# 25. 빠른 위치 판단 예시

## 주문 생성 Service

```text
주문
→ market

주문 기능
→ order

업무 흐름
→ application
```

결과:

```text
market/order/application/CheckoutService.java
```

## Order Entity

```text
market/order/domain/Order.java
```

## OrderRepository

```text
market/order/infrastructure/OrderRepository.java
```

## CartController

```text
market/cart/presentation/CartController.java
```

## 다른 모듈에서 상품 가격 조회

Product 루트에:

```text
product/ProductQuery.java
product/ProductInfo.java
```

를 만들고 외부에서는 이것만 사용합니다.

---

# 26. 최종 규칙 요약

1. Base Package 바로 아래가 Spring Modulith 모듈이다.

```text
member
product
market
payment
settlement
notification
```

2. 모듈 Root의 `UseCase / Query / DTO`가 외부 공개 계약이다.

3. `application / domain / infrastructure / presentation`은 모듈 내부 구현이다.

4. 다른 모듈 Repository를 직접 사용하지 않는다.

```text
X PostRepository
O ProductQuery
```

5. 다른 모듈 Entity를 직접 사용하지 않는다.

```text
X Post
O ProductInfo
```

6. 모듈 간에는 ID 또는 공개 DTO를 사용한다.

7. Cart / Order / Shipment / Refund는 현재 하나의 Market 모듈 내부 기능이다.

8. 담당자가 다르다는 이유로 Modulith 모듈을 나누지 않는다.

9. common에는 비즈니스 로직을 넣지 않는다.

10. PR 전에 Modulith 구조 검증 테스트를 실행한다.

---

# 27. 한 장으로 보는 호출 흐름

```text
                    PlantMarketApplication
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
      Member               Product              Market
        │                    │                    │
   Public API          Public API          Public API
        │                    │                    │
 application          application     ┌────┼────┬────┐
 domain               domain          cart order shipment refund
 infrastructure       infrastructure
 presentation         presentation

                             │
                          Payment
                             │
                        Public API
                             │
                 application / domain
                             │
                     infrastructure

                             │
                         Settlement
```

정상 접근:

```text
다른 모듈
   ↓
UseCase / Query / DTO
   ↓
Application
   ↓
Domain
   ↓
Infrastructure
```

잘못된 접근:

```text
다른 모듈
   ↓
Repository        X
Entity            X
Internal Service  X
```

---

# 28. 최종 목표

현재 프로젝트는:

```text
하나의 Spring Boot
하나의 PostgreSQL
```

이지만 코드에서는:

```text
Member
Product
Market
Payment
Settlement
Notification
```

이 서로 독립적인 서비스처럼 경계를 지키도록 만듭니다.

나중에 필요하면:

```text
Product Module
→ Product Service

Payment Module
→ Payment Service
```

처럼 실제 MSA로 분리할 수 있습니다.

개발할 때 가장 먼저 생각할 질문:

> **이 클래스가 어느 모듈의 책임인가?**

다른 모듈 기능이 필요할 때 생각할 질문:

> **상대 모듈의 Repository를 가져올까?** 가 아니라  
> **상대 모듈이 어떤 Public API를 제공해야 할까?**

이 원칙을 기준으로 개발하면 됩니다.
