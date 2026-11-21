package com.justcommit.backend.product.product.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.client.member.MemberQuery;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.event.ProductAdminHidden;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.species.domain.Species;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 관리자 강제 숨김 · 해제 (#623): 사유 검증과 이벤트(알림 · 상태 동기화) 발행
class AdminProductServiceTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long PRODUCT_ID = 10L;
    private static final Long SELLER_ID = 7L;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T06:00:00Z"), SEOUL);

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductEventPublisher productEventPublisher = mock(ProductEventPublisher.class);
    private final AdminProductService service = new AdminProductService(
            productRepository, mock(MemberQuery.class), productEventPublisher, CLOCK);

    private Product product;

    @BeforeEach
    void setUp() {
        Species species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "name", "몬스테라");
        product = Product.create(species, SELLER_ID, "몬스테라 분양", "설명", new BigDecimal("30000"),
                null, null, null, List.of("https://example.com/a.jpg"));
        ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
        ReflectionTestUtils.setField(product, "status", ProductStatus.ACTIVE);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));
    }

    @Test
    @DisplayName("강제 숨김하면 사유(앞뒤 공백 제거)를 저장하고 상태 동기화 + 판매자 알림 이벤트를 보냄")
    void hide() {
        service.changeHidden(ADMIN_ID, PRODUCT_ID, true, "  허위 매물  ");

        assertThat(product.isAdminHidden()).isTrue();
        assertThat(product.getAdminHiddenReason()).isEqualTo("허위 매물");
        verify(productEventPublisher).statusChanged(PRODUCT_ID);
        ArgumentCaptor<ProductAdminHidden> event = ArgumentCaptor.forClass(ProductAdminHidden.class);
        verify(productEventPublisher).adminHidden(event.capture());
        assertThat(event.getValue()).isEqualTo(new ProductAdminHidden(PRODUCT_ID, SELLER_ID, "몬스테라 분양",
                "허위 매물", LocalDateTime.of(2026, 11, 21, 15, 0)));
    }

    @Test
    @DisplayName("이미 강제 숨김한 상품이면 이벤트를 다시 보내지 않음")
    void hideTwice() {
        product.hideByAdmin("허위 매물", LocalDateTime.of(2026, 11, 20, 9, 0));

        service.changeHidden(ADMIN_ID, PRODUCT_ID, true, "다른 사유");

        verifyNoInteractions(productEventPublisher);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("숨김 사유가 없으면 400 INVALID_ADMIN_HIDE_REASON, 상품은 그대로")
    void hideWithoutReason(String reason) {
        assertThatThrownBy(() -> service.changeHidden(ADMIN_ID, PRODUCT_ID, true, reason))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.INVALID_ADMIN_HIDE_REASON);
        assertThat(product.isAdminHidden()).isFalse();
        verifyNoInteractions(productEventPublisher);
    }

    @Test
    @DisplayName("숨김 사유가 200자를 넘으면 400 INVALID_ADMIN_HIDE_REASON")
    void hideWithTooLongReason() {
        assertThatThrownBy(() -> service.changeHidden(ADMIN_ID, PRODUCT_ID, true, "가".repeat(201)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.INVALID_ADMIN_HIDE_REASON);
    }

    @Test
    @DisplayName("해제하면 상태 동기화만 보내고 알림은 없음, 판매자 숨김은 그대로")
    void unhide() {
        product.changeHidden(true);
        product.hideByAdmin("허위 매물", LocalDateTime.of(2026, 11, 20, 9, 0));

        service.changeHidden(ADMIN_ID, PRODUCT_ID, false, null);

        assertThat(product.isAdminHidden()).isFalse();
        assertThat(product.isHidden()).isTrue();
        verify(productEventPublisher).statusChanged(PRODUCT_ID);
        verify(productEventPublisher, never()).adminHidden(any());
    }

    @Test
    @DisplayName("강제 숨김하지 않은 상품을 해제하면 아무것도 안 함")
    void unhideNotHidden() {
        service.changeHidden(ADMIN_ID, PRODUCT_ID, false, null);

        verifyNoInteractions(productEventPublisher);
    }

    @Test
    @DisplayName("없는 상품이면 404 PRODUCT_NOT_FOUND")
    void notFound() {
        given(productRepository.findWithLockById(anyLong())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeHidden(ADMIN_ID, 99L, true, "허위 매물"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
    }
}
