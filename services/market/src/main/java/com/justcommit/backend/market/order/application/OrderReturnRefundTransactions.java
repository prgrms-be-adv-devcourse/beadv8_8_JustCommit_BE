package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderItemReturnedEvent;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.client.payment.RefundOutcome;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.ReturnRequest;
import com.justcommit.backend.market.order.domain.ReturnRequestStatus;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.event.OrderItemReturned;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.ReturnRequestRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// 반품 도착 처리와 반품 환불의 짧은 트랜잭션들 (#384). 환불 REST는 이 클래스 밖, 트랜잭션 사이에서 한다 (#282 구조).
// - 도착 처리: 주문 → 판매자 주문을 잠그고 반품 배송 중이면 반품 환불 중으로 바꾼다 (환불 시도 기록은 비움, payment 안 부름)
// - 선점: 첫 시도는 attempted_at이 빈 행만 바꾸는 조건부 UPDATE, 재시도는 SKIP LOCKED + 유예 확인 + 횟수 +1
// - 결과 반영: 「반품 환불 중」일 때만 조건부 UPDATE라 두 파드, 늦은 응답이 겹쳐도 한 번만 반영된다
// 배송비: 선점할 때마다 그때의 판매자 주문 상태로 다시 정한다. 취소 완료를 뺀 모든 상품이 반품 환불 중 또는 반품 완료면
// (전부 반품) 원래 배송비도 같은 키(sellerOrderId)로 환불한다. 도착 처리는 판매자 주문 잠금으로 한 줄로 서므로
// 마지막에 도착한 상품의 선점은 나머지가 모두 반품 흐름인 상태를 보고, 배송비가 빠지지 않는다.
// 둘 이상이 배송비를 실어도 payment는 같은 키면 ALREADY_REFUNDED로 처음 결과를 돌려준다 (돈은 한 번).
// 같은 클래스 안 호출로는 @Transactional이 걸리지 않으므로 TransactionTemplate으로 경계를 나눈다 (OrderCancelTransactions와 같음)
@Slf4j
@Component
public class OrderReturnRefundTransactions {

    // 트랜잭션 밖에서 부를 반품 환불 한 건. shippingFee가 있으면 원래 배송비도 같은 키(sellerOrderId)로 환불한다
    // attemptCount: 이번 시도까지의 복구 선점 횟수 (첫 호출은 0)
    public record ReturnRefundClaim(Long orderItemId, Long orderId, Long sellerOrderId, Long buyerId,
                                    BigDecimal price, BigDecimal shippingFee, int attemptCount) {

        public boolean lastAttempt() {
            return attemptCount >= OrderCancelTransactions.MAX_RECOVERY_ATTEMPTS;
        }
    }

    private final TransactionTemplate transactionTemplate;
    private final OrdersRepository ordersRepository;
    private final SellerOrderRepository sellerOrderRepository;
    private final OrdersItemRepository ordersItemRepository;
    private final ReturnRequestRepository returnRequestRepository;
    private final OutboxPublisher outboxPublisher;
    private final SellerOrderEventPublisher sellerOrderEventPublisher;
    private final SellerOrderCompletion sellerOrderCompletion;

    public OrderReturnRefundTransactions(PlatformTransactionManager transactionManager,
                                         OrdersRepository ordersRepository,
                                         SellerOrderRepository sellerOrderRepository,
                                         OrdersItemRepository ordersItemRepository,
                                         ReturnRequestRepository returnRequestRepository,
                                         OutboxPublisher outboxPublisher,
                                         SellerOrderEventPublisher sellerOrderEventPublisher,
                                         SellerOrderCompletion sellerOrderCompletion) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.ordersRepository = ordersRepository;
        this.sellerOrderRepository = sellerOrderRepository;
        this.ordersItemRepository = ordersItemRepository;
        this.returnRequestRepository = returnRequestRepository;
        this.outboxPublisher = outboxPublisher;
        this.sellerOrderEventPublisher = sellerOrderEventPublisher;
        this.sellerOrderCompletion = sellerOrderCompletion;
    }

    // ===== 반품 도착 처리

    // 반품 배송이 판매자에게 도착한 반품 신청 하나. 주문 → 판매자 주문을 잠근 뒤 승인 상태이고 주문상품이 반품 배송 중일 때만
    // 반품 환불 중으로 바꾼다. 이미 바뀌었으면(다른 파드, 앞 주기) 건너뛴다
    public boolean markArrived(Long returnRequestId) {
        Boolean arrived = transactionTemplate.execute(status -> {
            Long orderItemId = returnRequestRepository.findOrderItemIdById(returnRequestId).orElse(null);
            if (orderItemId == null) {
                return false;
            }
            Long orderId = ordersItemRepository.findOrderIdByItemId(orderItemId).orElseThrow();
            Long sellerOrderId = ordersItemRepository.findSellerOrderIdByItemId(orderItemId).orElseThrow();
            ordersRepository.findLockedById(orderId).orElseThrow();
            sellerOrderRepository.findLockedById(sellerOrderId).orElseThrow();
            ReturnRequest returnRequest = returnRequestRepository.findById(returnRequestId).orElseThrow();
            OrdersItem item = ordersItemRepository.findById(orderItemId).orElseThrow();
            if (returnRequest.getStatus() != ReturnRequestStatus.APPROVED
                    || item.getStatus() != OrderItemStatus.RETURN_IN_TRANSIT) {
                return false;
            }
            item.arriveReturn();
            return true;
        });
        if (Boolean.TRUE.equals(arrived)) {
            log.info("반품 도착, 반품 환불 중으로 바꿈: returnRequestId={}", returnRequestId);
        }
        return Boolean.TRUE.equals(arrived);
    }

    // ===== 반품 환불 선점

    // 첫 환불: 도착 처리만 된(attempted_at 비어 있음) 주문상품을 조건부 UPDATE로 선점한다. 첫 호출은 횟수에 넣지 않는다.
    // 다른 파드가 먼저 바꿨으면 빈 값
    public Optional<ReturnRefundClaim> claimFirst(Long orderItemId) {
        return Optional.ofNullable(transactionTemplate.execute(status -> {
            if (ordersItemRepository.startRefund(List.of(orderItemId), LocalDateTime.now(),
                    OrderItemStatus.RETURN_PROCESSING) == 0) {
                return null;
            }
            // 벌크 UPDATE가 영속성 컨텍스트를 비웠으므로 다시 읽는다
            OrdersItem item = ordersItemRepository.findById(orderItemId).orElseThrow();
            return claimOf(item, item.getAttemptCount());
        }));
    }

    // 결과 불명 반품 환불 하나를 선점한다 (SKIP LOCKED). 잠근 뒤 반품 환불 중, 유예 경과를 다시 본다.
    // 복구를 이미 5번 했으면 환불을 부르지 않고 확인 필요로 바꾼다 (취소 환불과 같은 정책).
    // 확인 필요 전환은 선점 트랜잭션이 끝난 뒤 주문 → 판매자 주문 잠금으로 한다 (잠금 순서, OrderCancelTransactions와 같음)
    public Optional<ReturnRefundClaim> claimRetry(Long orderItemId) {
        RetryClaim retry = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            OrdersItem item = ordersItemRepository.findByIdForUpdateSkipLocked(orderItemId).orElse(null);
            if (item == null || item.getStatus() != OrderItemStatus.RETURN_PROCESSING || item.getAttemptedAt() == null
                    || item.getAttemptedAt().isAfter(now.minus(OrderCancelTransactions.RECOVERY_GRACE))) {
                return null;
            }
            if (item.getAttemptCount() >= OrderCancelTransactions.MAX_RECOVERY_ATTEMPTS) {
                SellerOrder sellerOrder = item.getSellerOrder();
                return new RetryClaim(new ReturnRefundClaim(orderItemId, sellerOrder.getOrder().getId(),
                        sellerOrder.getId(), sellerOrder.getOrder().getBuyerId(), item.getPrice(), null,
                        item.getAttemptCount()), true, item.getLastError());
            }
            ReturnRefundClaim claim = claimOf(item, item.getAttemptCount() + 1);
            ordersItemRepository.claimRefundRetry(orderItemId, now, OrderItemStatus.RETURN_PROCESSING);
            return new RetryClaim(claim, false, null);
        });
        if (retry == null) {
            return Optional.empty();
        }
        if (retry.exhausted()) {
            requireReview(retry.claim(), retry.lastError(), "복구 횟수를 다 쓰고 유예가 지남");
            return Optional.empty();
        }
        return Optional.of(retry.claim());
    }

    private record RetryClaim(ReturnRefundClaim claim, boolean exhausted, String lastError) {
    }

    private ReturnRefundClaim claimOf(OrdersItem item, int attemptCount) {
        SellerOrder sellerOrder = item.getSellerOrder();
        Orders order = sellerOrder.getOrder();
        return new ReturnRefundClaim(item.getId(), order.getId(), sellerOrder.getId(), order.getBuyerId(),
                item.getPrice(), shippingFeeToRefund(sellerOrder), attemptCount);
    }

    // 전부 반품(취소 완료를 뺀 모든 상품이 반품 환불 중 또는 반품 완료)이면 원래 배송비를 환불한다.
    // 반품이 확인 필요가 된 상품, 구매 확정 상품, 아직 반품 중인 상품이 있으면 배송비는 없다
    static BigDecimal shippingFeeToRefund(SellerOrder sellerOrder) {
        if (sellerOrder.getShippingFee().signum() <= 0) {
            return null;
        }
        boolean wholeSellerOrderReturned = sellerOrder.getItems().stream()
                .allMatch(item -> item.getStatus() == OrderItemStatus.CANCELLED
                        || item.getStatus() == OrderItemStatus.RETURN_PROCESSING
                        || item.getStatus() == OrderItemStatus.RETURNED);
        return wholeSellerOrderReturned ? sellerOrder.getShippingFee() : null;
    }

    // ===== 환불 결과 반영 (반품 환불 중일 때만, 한 번만)

    public boolean applyRefundOutcome(ReturnRefundClaim claim, RefundOutcome outcome) {
        return switch (outcome.type()) {
            case REFUNDED -> completeReturn(claim);
            case REJECTED -> requireReview(claim, outcome.reason(), "환불 거절(4xx)");
            case UNKNOWN -> claim.lastAttempt()
                    ? requireReview(claim, outcome.reason(), "마지막 복구도 결과 불명")
                    : keepProcessing(claim, outcome.reason());
        };
    }

    // 환불 확정: 반품 완료, 반품 신청 완료, 판매 재개 Outbox와 구매자 알림 이벤트, 그 뒤 판매자 주문 종료 판정 (#383 코드)
    private boolean completeReturn(ReturnRefundClaim claim) {
        Boolean applied = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            ordersRepository.findLockedById(claim.orderId()).orElseThrow();
            sellerOrderRepository.findLockedById(claim.sellerOrderId()).orElseThrow();
            if (ordersItemRepository.applyRefund(claim.orderItemId(), OrderItemStatus.RETURNED, null, now,
                    OrderItemStatus.RETURN_PROCESSING) == 0) {
                return false;
            }
            // 벌크 UPDATE가 영속성 컨텍스트를 비웠으므로 다시 읽는다
            ReturnRequest returnRequest = returnRequestRepository.findByOrderItemId(claim.orderItemId()).orElseThrow();
            returnRequest.complete();
            OrdersItem item = ordersItemRepository.findById(claim.orderItemId()).orElseThrow();
            SellerOrder sellerOrder = sellerOrderRepository.findById(claim.sellerOrderId()).orElseThrow();
            Orders order = sellerOrder.getOrder();

            // 판매 재개는 환불이 확정된 뒤에만 product에 알린다 (ADR 0016)
            outboxPublisher.publish(new OrderItemReturned(order.getId(), item.getId(), item.getProductId()));
            sellerOrderEventPublisher.publishAfterCommit(new OrderItemReturnedEvent(returnRequest.getId(),
                    item.getId(), order.getId(), sellerOrder.getId(), order.getBuyerId(), sellerOrder.getSellerId(),
                    item.getProductName(), item.getPrice(), now));

            if (order.getStatus() == OrderStatus.PAID) {
                sellerOrderCompletion.complete(order, sellerOrder);
            }
            return true;
        });
        if (Boolean.TRUE.equals(applied)) {
            log.info("반품 환불 확정, 반품 완료: orderItemId={}, shippingFeeRefunded={}",
                    claim.orderItemId(), claim.shippingFee() != null);
        }
        logNotApplied(claim, Boolean.TRUE.equals(applied), OrderItemStatus.RETURNED);
        return Boolean.TRUE.equals(applied);
    }

    // 확인 필요: 사람이 확인한다. 반품 신청은 승인 상태로 남고, 판매자 주문은 끝나지 않아 정산 이벤트가 나가지 않는다
    private boolean requireReview(ReturnRefundClaim claim, String reason, String why) {
        Boolean applied = transactionTemplate.execute(status -> {
            ordersRepository.findLockedById(claim.orderId()).orElseThrow();
            sellerOrderRepository.findLockedById(claim.sellerOrderId()).orElseThrow();
            return ordersItemRepository.applyRefund(claim.orderItemId(), OrderItemStatus.REVIEW_REQUIRED, reason,
                    LocalDateTime.now(), OrderItemStatus.RETURN_PROCESSING) == 1;
        });
        if (Boolean.TRUE.equals(applied)) {
            log.atError()
                    .setMessage("반품 환불 결과를 확정하지 못해 확인 필요로 전환")
                    .addKeyValue("orderItemId", claim.orderItemId())
                    .addKeyValue("orderId", claim.orderId())
                    .addKeyValue("attemptCount", claim.attemptCount())
                    .addKeyValue("lastError", reason)
                    .addKeyValue("reason", why)
                    .log();
        }
        logNotApplied(claim, Boolean.TRUE.equals(applied), OrderItemStatus.REVIEW_REQUIRED);
        return Boolean.TRUE.equals(applied);
    }

    // 결과 불명: 반품 환불 중 그대로 두고 원인만 남긴다. 유예가 지나면 복구가 같은 키로 다시 요청한다
    private boolean keepProcessing(ReturnRefundClaim claim, String reason) {
        Boolean applied = transactionTemplate.execute(status -> ordersItemRepository.applyRefund(claim.orderItemId(),
                OrderItemStatus.RETURN_PROCESSING, reason, LocalDateTime.now(), OrderItemStatus.RETURN_PROCESSING) == 1);
        if (Boolean.TRUE.equals(applied)) {
            log.warn("반품 환불 결과 불명, 반품 환불 중 유지: orderItemId={}, attemptCount={}, reason={}",
                    claim.orderItemId(), claim.attemptCount(), reason);
        }
        logNotApplied(claim, Boolean.TRUE.equals(applied), OrderItemStatus.RETURN_PROCESSING);
        return Boolean.TRUE.equals(applied);
    }

    private void logNotApplied(ReturnRefundClaim claim, boolean applied, OrderItemStatus to) {
        if (!applied) {
            // 다른 파드, 시도가 이미 반영함. 늦게 온 결과는 버린다
            log.info("반품 환불 결과를 반영하지 않음(이미 반품 환불 중이 아님): orderItemId={}, to={}",
                    claim.orderItemId(), to);
        }
    }
}
