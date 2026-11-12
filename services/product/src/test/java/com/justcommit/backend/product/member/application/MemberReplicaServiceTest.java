package com.justcommit.backend.product.member.application;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.product.member.infrastructure.MemberReplicaRepository;
import com.justcommit.backend.product.product.event.ProductStatusChanged;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 판매자 탈퇴 정리의 상품 이벤트 발행 (#338). 잠금 · 삭제 동작은 SellerWithdrawalReleaseIntegrationTest
@ExtendWith(MockitoExtension.class)
@DisplayName("회원 복제본 반영 · 탈퇴 정리 이벤트 발행")
class MemberReplicaServiceTest {

    private static final Long SELLER_ID = 7L;
    private static final OffsetDateTime OCCURRED_AT = OffsetDateTime.of(2026, 11, 12, 10, 0, 0, 0, ZoneOffset.ofHours(9));

    @Mock
    private MemberReplicaRepository memberReplicaRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OutboxPublisher outboxPublisher;

    @InjectMocks
    private MemberReplicaService memberReplicaService;

    @Test
    @DisplayName("탈퇴로 상품이 삭제되면 잠근 상품마다 ProductStatusChanged를 발행")
    void withdrawnPublishesForEachLockedProduct() {
        given(productRepository.lockChangeableBySeller(SELLER_ID)).willReturn(List.of(10L, 11L));
        given(productRepository.deleteOnSaleBySeller(SELLER_ID)).willReturn(2);

        assertThat(memberReplicaService.applyWithdrawn(SELLER_ID, OCCURRED_AT)).isEqualTo(2);

        verify(outboxPublisher).publish(new ProductStatusChanged(10L));
        verify(outboxPublisher).publish(new ProductStatusChanged(11L));
    }

    @Test
    @DisplayName("삭제된 상품이 없으면 이벤트를 발행하지 않음 (같은 탈퇴 이벤트가 다시 와도 발행 없음)")
    void withdrawnNothingDeletedPublishesNothing() {
        given(productRepository.lockChangeableBySeller(SELLER_ID)).willReturn(List.of(10L));
        given(productRepository.deleteOnSaleBySeller(SELLER_ID)).willReturn(0);

        assertThat(memberReplicaService.applyWithdrawn(SELLER_ID, OCCURRED_AT)).isZero();

        verify(outboxPublisher, never()).publish(any());
    }
}
