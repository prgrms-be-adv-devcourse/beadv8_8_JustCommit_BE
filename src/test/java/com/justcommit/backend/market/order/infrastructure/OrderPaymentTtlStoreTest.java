package com.justcommit.backend.market.order.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPaymentTtlStoreTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void writesTtlOnlyAfterOrderTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
        OrderPaymentTtlStore store = new OrderPaymentTtlStore(redisTemplate);

        store.startAfterCommit(99L, expiresAt);

        verify(redisTemplate, never()).opsForValue();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();

        verify(valueOperations).set(eq("order:payment:ttl:99"), eq("1"), any(Duration.class));
    }
}
