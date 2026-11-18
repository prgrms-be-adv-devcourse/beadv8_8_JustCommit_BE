package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderResult;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.SellerOrderPaidEvent;
import com.justcommit.backend.market.SellerOrderResult;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.client.payment.PaymentOutcome;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.event.OrderFailed;
import com.justcommit.backend.market.order.event.OrderPaid;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import com.justcommit.backend.market.shipment.application.ShipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.OptionalInt;

// 주문 결제의 짧은 트랜잭션들 (ADR 0023). REST 호출은 이 클래스 밖, 트랜잭션 사이에서 한다.
// TX1 주문 저장 → (상품 예약 REST) → TX2 결제 시작 → (결제 REST) → TX3 결과 반영
// 같은 클래스 안 호출로는 @Transactional이 걸리지 않으므로 TransactionTemplate으로 경계를 나눈다.
@Slf4j
@Component
public class OrderPaymentTransactions {

    static final Duration PAYMENT_WINDOW = Duration.ofMinutes(10);
    // 마지막 시도에서 이만큼 지나야 복구가 집는다. 결제 호출 한 번의 최악(약 8.2초)보다 길다
    static final Duration RECOVERY_GRACE = Duration.ofSeconds(10);
    // 복구 선점 횟수 상한. 이만큼 해도 결과를 모르면 확인 필요 (첫 결제 호출은 세지 않음)
    static final int MAX_RECOVERY_ATTEMPTS = 5;

    // 복구 선점 결과. RETRY면 트랜잭션 밖에서 같은 orderId로 결제를 다시 부른다
    public record RecoveryClaim(Type type, Long orderId, int attemptNo, int attemptCount, Long buyerId,
                                BigDecimal amount) {

        public enum Type {
            RETRY,
            REVIEW_REQUIRED,
            SKIPPED
        }

        static RecoveryClaim skipped(Long orderId) {
            return new RecoveryClaim(Type.SKIPPED, orderId, 0, 0, null, null);
        }

        public boolean lastAttempt() {
            return attemptCount >= MAX_RECOVERY_ATTEMPTS;
        }
    }

    // 결제를 누가 불렀는지. 연결 실패를 첫 호출은 확정 실패로, 복구는 결과 불명으로 본다
    public enum AttemptSource {
        FIRST_CALL,
        RECOVERY
    }

    private final TransactionTemplate transactionTemplate;
    private final OrdersRepository ordersRepository;
    private final OutboxPublisher outboxPublisher;
    private final OrderQuery orderQuery;
    private final SellerOrderEventPublisher sellerOrderEventPublisher;
    private final ShipmentService shipmentService;
    private final CartService cartService;

    public OrderPaymentTransactions(PlatformTransactionManager transactionManager,
                                    OrdersRepository ordersRepository,
                                    OutboxPublisher outboxPublisher,
                                    OrderQuery orderQuery,
                                    SellerOrderEventPublisher sellerOrderEventPublisher,
                                    ShipmentService shipmentService,
                                    CartService cartService) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.ordersRepository = ordersRepository;
        this.outboxPublisher = outboxPublisher;
        this.orderQuery = orderQuery;
        this.sellerOrderEventPublisher = sellerOrderEventPublisher;
        this.shipmentService = shipmentService;
        this.cartService = cartService;
    }

    // TX1: 주문 · 판매자 주문 · 주문상품을 결제 대기로 저장하고 커밋한다
    public Orders saveNewOrder(Orders order) {
        return transactionTemplate.execute(status -> ordersRepository.saveAndFlush(order));
    }

    // 상품 예약이 실패해 결제 전에 주문을 끝낸다. 예약이 남았을 수 있으면(타임아웃 · 5xx) 해제 이벤트를 함께 남긴다
    public void failBeforePayment(Long orderId, boolean releaseReservation) {
        transactionTemplate.executeWithoutResult(status -> {
            Orders order = ordersRepository.findLockedById(orderId).orElseThrow();
            if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
                return;
            }
            order.fail();
            if (releaseReservation) {
                outboxPublisher.publish(new OrderFailed(orderId));
            }
        });
    }

    // TX2: 결제 대기 · 기한 안이면 결제 처리 중으로 바꾸고 이번 시도 번호를 돌려준다. 바꾸지 못하면 빈 값 (결제를 부르지 않음)
    public OptionalInt startAttempt(Long orderId) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            int updated = ordersRepository.startPaymentAttempt(orderId, now, now.minus(PAYMENT_WINDOW),
                    OrderStatus.PAYMENT_PENDING, OrderStatus.PAYMENT_PROCESSING);
            if (updated == 0) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(ordersRepository.findById(orderId).orElseThrow().getAttemptNo());
        });
    }

    // TX3: 결제 처리 중이고 자기 시도 번호일 때만 결과를 반영한다. 반영하지 못하면 false (아무것도 바꾸지 않음).
    // 10분 기한은 보지 않는다. 기한이 지났어도 결제됐으면 결제 완료로 처리한다
    public boolean applyOutcome(Long orderId, int attemptNo, PaymentOutcome outcome, AttemptSource source) {
        return applyOutcome(orderId, attemptNo, outcome, source, false);
    }

    // restoreCartOnFailure: 확정 실패면 같은 트랜잭션에서 장바구니를 되돌린다.
    // create는 확정 실패면 장바구니를 지우지 않았으니 false, repayment는 create가 이미 지웠으니 true (ADR 0023)
    public boolean applyOutcome(Long orderId, int attemptNo, PaymentOutcome outcome, AttemptSource source,
                                boolean restoreCartOnFailure) {
        return apply(orderId, attemptNo, outcome, targetStatus(outcome, source), restoreCartOnFailure);
    }

    // 복구 결과 반영. 마지막(5번째) 복구까지 결과를 모르면 그 자리에서 확인 필요로 바꾼다
    public boolean applyRecoveryOutcome(RecoveryClaim claim, PaymentOutcome outcome) {
        OrderStatus to = targetStatus(outcome, AttemptSource.RECOVERY);
        if (to == OrderStatus.PAYMENT_PROCESSING && claim.lastAttempt()) {
            return requireReviewAfterLastAttempt(claim, outcome);
        }
        // 복구까지 온 주문은 create가 장바구니에서 지웠으므로, 확정 실패면 같은 트랜잭션에서 되돌린다 (만료와 같음)
        return apply(claim.orderId(), claim.attemptNo(), outcome, to, true);
    }

    // 복구 선점 (짧은 TX): 다른 파드 · 요청이 잡은 주문은 건너뛴다. 잠근 뒤 결제 처리 중 · 유예 경과를 다시 본다.
    // 다른 파드가 막 선점해 커밋했으면 attempted_at이 방금이라 여기서 걸러진다.
    // 복구를 이미 5번 했으면 결제를 부르지 않고 확인 필요로 바꾼다 (5번째 선점 뒤 반영 없이 유예가 지난 경우 포함)
    public RecoveryClaim claimForRecovery(Long orderId) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            Orders order = ordersRepository.findByIdForUpdateSkipLocked(orderId).orElse(null);
            if (order == null || order.getStatus() != OrderStatus.PAYMENT_PROCESSING
                    || order.getAttemptedAt() == null || order.getAttemptedAt().isAfter(now.minus(RECOVERY_GRACE))) {
                return RecoveryClaim.skipped(orderId);
            }
            if (order.getAttemptCount() >= MAX_RECOVERY_ATTEMPTS) {
                order.requireReview();
                ordersRepository.saveAndFlush(order);
                logReviewRequired(order.getId(), order.getAttemptNo(), order.getAttemptCount(), order.getLastError(),
                        "복구 횟수를 다 쓰고 유예가 지남");
                return new RecoveryClaim(RecoveryClaim.Type.REVIEW_REQUIRED, orderId, order.getAttemptNo(),
                        order.getAttemptCount(), order.getBuyerId(), order.getTotalAmount());
            }
            order.claimRecovery(now);
            ordersRepository.saveAndFlush(order);
            return new RecoveryClaim(RecoveryClaim.Type.RETRY, orderId, order.getAttemptNo(),
                    order.getAttemptCount(), order.getBuyerId(), order.getTotalAmount());
        });
    }

    private boolean requireReviewAfterLastAttempt(RecoveryClaim claim, PaymentOutcome outcome) {
        Boolean applied = transactionTemplate.execute(status -> ordersRepository.requireReviewIfAttempt(
                claim.orderId(), claim.attemptNo(), outcome.reason(), LocalDateTime.now(),
                OrderStatus.PAYMENT_PROCESSING, OrderStatus.REVIEW_REQUIRED) == 1);
        if (Boolean.TRUE.equals(applied)) {
            logReviewRequired(claim.orderId(), claim.attemptNo() + 1, claim.attemptCount(), outcome.reason(),
                    "마지막 복구도 결과 불명");
        } else {
            log.info("결제 결과를 반영하지 않음(시도 번호 불일치 또는 상태 변경): orderId={}, attemptNo={}, outcome={}",
                    claim.orderId(), claim.attemptNo(), outcome.type());
        }
        return Boolean.TRUE.equals(applied);
    }

    // 사람이 확인해야 하는 주문. 운영 알림 · 런북의 시작점이라 ERROR 한 줄로 남긴다
    private void logReviewRequired(Long orderId, int attemptNo, int attemptCount, String lastError, String reason) {
        log.atError()
                .setMessage("결제 결과를 확정하지 못해 확인 필요로 전환")
                .addKeyValue("orderId", orderId)
                .addKeyValue("attemptNo", attemptNo)
                .addKeyValue("attemptCount", attemptCount)
                .addKeyValue("lastError", lastError)
                .addKeyValue("reason", reason)
                .log();
    }

    private boolean apply(Long orderId, int attemptNo, PaymentOutcome outcome, OrderStatus to,
                          boolean restoreCartOnFailure) {
        String lastError = (to == OrderStatus.PAID || to == OrderStatus.PAYMENT_PENDING) ? null : outcome.reason();
        Boolean applied = transactionTemplate.execute(status -> {
            int updated = ordersRepository.applyPaymentAttempt(orderId, attemptNo, to, lastError,
                    LocalDateTime.now(), OrderStatus.PAYMENT_PROCESSING);
            if (updated == 0) {
                return false;
            }
            if (to == OrderStatus.PAID) {
                completePayment(orderId);
            } else if (to == OrderStatus.PAYMENT_FAILED) {
                // 잡아 둔 예약은 product가 풀도록 알린다. 결과를 모르는 동안은 보내지 않는다
                outboxPublisher.publish(new OrderFailed(orderId));
                if (restoreCartOnFailure) {
                    restoreCart(orderId);
                }
            }
            return true;
        });
        logOutcome(orderId, attemptNo, outcome, to, Boolean.TRUE.equals(applied));
        return Boolean.TRUE.equals(applied);
    }

    // 주문상품을 주문 당시 가격으로 장바구니에 되돌린다. 이미 담긴 상품은 건너뛴다 (CartService.restoreItem)
    private void restoreCart(Long orderId) {
        Orders order = ordersRepository.findById(orderId).orElseThrow();
        order.getSellerOrders().stream()
                .flatMap(sellerOrder -> sellerOrder.getItems().stream())
                .forEach(item -> cartService.restoreItem(order.getBuyerId(), item.getProductId(), item.getPrice()));
    }

    public Orders getOrder(Long orderId) {
        return transactionTemplate.execute(status -> ordersRepository.findById(orderId).orElseThrow());
    }

    static OrderStatus targetStatus(PaymentOutcome outcome, AttemptSource source) {
        return switch (outcome.type()) {
            case PAID -> OrderStatus.PAID;
            case INSUFFICIENT_BALANCE -> OrderStatus.PAYMENT_PENDING;
            case REJECTED -> OrderStatus.PAYMENT_FAILED;
            // 첫 호출이 안 닿았으면 결제가 없었다. 복구 호출이 안 닿은 것은 앞선 시도의 결과를 알려 주지 않는다
            case NOT_DELIVERED -> source == AttemptSource.FIRST_CALL
                    ? OrderStatus.PAYMENT_FAILED : OrderStatus.PAYMENT_PROCESSING;
            case UNKNOWN -> OrderStatus.PAYMENT_PROCESSING;
        };
    }

    // 결제 완료 후속 처리: 판매 완료 Outbox · 판매자 알림 · 배송 행. 결과 반영과 같은 트랜잭션 (ADR 0016 · 0023)
    private void completePayment(Long orderId) {
        outboxPublisher.publish(new OrderPaid(orderId));
        OrderResult result = orderQuery.getOrder(orderId);
        LocalDateTime paidAt = LocalDateTime.now();
        for (SellerOrderResult sellerOrder : result.sellerOrders()) {
            sellerOrderEventPublisher.publishAfterCommit(new SellerOrderPaidEvent(
                    sellerOrder.id(), sellerOrder.sellerId(), result.buyerId(), paidAt));
        }
        // #475 이후 배송 행(READY)만 만들고 택배사는 부르지 않는다. 예약은 택배 예약 큐가 한다
        shipmentService.createShipments(result);
    }

    private void logOutcome(Long orderId, int attemptNo, PaymentOutcome outcome, OrderStatus to, boolean applied) {
        if (!applied) {
            // 다른 시도가 이미 반영했거나 선점함. 늦게 온 이전 시도 결과는 버린다
            log.info("결제 결과를 반영하지 않음(시도 번호 불일치 또는 상태 변경): orderId={}, attemptNo={}, outcome={}",
                    orderId, attemptNo, outcome.type());
        } else if (to == OrderStatus.PAYMENT_PROCESSING) {
            log.warn("결제 결과 불명, 결제 처리 중 유지: orderId={}, attemptNo={}, reason={}",
                    orderId, attemptNo, outcome.reason());
        } else if (to == OrderStatus.PAYMENT_FAILED) {
            log.warn("결제 확정 실패, 주문 실패: orderId={}, attemptNo={}, reason={}",
                    orderId, attemptNo, outcome.reason());
        }
    }
}
