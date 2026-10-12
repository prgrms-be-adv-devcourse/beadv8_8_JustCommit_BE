package com.justcommit.backend.market.order.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaymentTtlStore {

    private final StringRedisTemplate redisTemplate;

    public void startAfterCommit(Long orderId, LocalDateTime expiresAt) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Duration remaining = Duration.between(LocalDateTime.now(), expiresAt);
                if (remaining.isNegative() || remaining.isZero()) {
                    return;
                }
                try {
                    redisTemplate.opsForValue().set("order:payment:ttl:" + orderId, "1", remaining);
                } catch (RuntimeException exception) {
                    // DB 만료 검사도 수행하므로 Redis 장애로 이미 확정된 주문을 실패 처리하지 않는다.
                    log.warn("주문 결제대기 TTL 저장 실패: orderId={}", orderId, exception);
                }
            }
        });
    }
}
