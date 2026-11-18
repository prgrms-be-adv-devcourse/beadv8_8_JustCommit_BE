package com.justcommit.backend.market.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.cart.infrastructure.CartItemRepository;
import com.justcommit.backend.market.cart.presentation.CartRecommendationResponse;
import com.justcommit.backend.market.client.ai.CartRecommendationQuery;
import com.justcommit.backend.market.client.ai.CartRecommendationQuery.Recommendation;
import com.justcommit.backend.market.client.product.ProductQuery;
import com.justcommit.backend.market.client.product.ProductResult;
import com.justcommit.backend.market.client.product.ProductStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CartRecommendationServiceTest {

    private static final long MEMBER_ID = 10L;

    @Mock CartItemRepository cartItemRepository;
    @Mock OrderQuery orderQuery;
    @Mock CartRecommendationQuery cartRecommendationQuery;
    @Mock ProductQuery productQuery;
    @InjectMocks CartRecommendationService service;

    @Test
    @DisplayName("장바구니 · 최근 90일 구매 확정 상품(최대 20개)으로 ai에 size의 2배를 요청하고, ai 순서대로 카드를 만든다")
    void recommendsInAiOrder() {
        givenCartAndPurchased(List.of(101L), List.of(90L));
        when(cartRecommendationQuery.recommendForCart(List.of(101L), List.of(90L), 8)).thenReturn(List.of(
                new Recommendation(202L, "이유 B"), new Recommendation(201L, "이유 A")));
        when(productQuery.getProducts(List.of(202L, 201L))).thenReturn(List.of(
                product(201L, 20L, ProductStatus.ACTIVE, false), product(202L, 21L, ProductStatus.ACTIVE, false)));

        List<CartRecommendationResponse> result = service.recommend(MEMBER_ID, 4);

        assertThat(result).extracting(CartRecommendationResponse::productId, CartRecommendationResponse::reason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(202L, "이유 B"),
                        org.assertj.core.groups.Tuple.tuple(201L, "이유 A"));
        assertThat(result.getFirst().title()).isEqualTo("상품 202");

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderQuery).getRecentConfirmedProductIds(eq(MEMBER_ID), since.capture(), eq(20));
        assertThat(since.getValue()).isBetween(
                LocalDateTime.now().minusDays(90).minusMinutes(1), LocalDateTime.now().minusDays(90).plusMinutes(1));
    }

    @Test
    @DisplayName("본인 상품 · 구매 불가 상품(판매 완료 · 숨김) · product 응답에 없는 상품 · 장바구니 상품은 뺀다")
    void excludesNotRecommendable() {
        givenCartAndPurchased(List.of(101L), List.of());
        when(cartRecommendationQuery.recommendForCart(anyList(), anyList(), anyInt())).thenReturn(List.of(
                new Recommendation(101L, "장바구니"),
                new Recommendation(301L, "본인 상품"),
                new Recommendation(302L, "판매 완료"),
                new Recommendation(303L, "숨김"),
                new Recommendation(304L, "응답에 없음"),
                new Recommendation(305L, "추천")));
        when(productQuery.getProducts(List.of(301L, 302L, 303L, 304L, 305L))).thenReturn(List.of(
                product(301L, MEMBER_ID, ProductStatus.ACTIVE, false),
                product(302L, 20L, ProductStatus.SOLD_OUT, false),
                product(303L, 20L, ProductStatus.ACTIVE, true),
                product(305L, 20L, ProductStatus.ACTIVE, false)));

        assertThat(service.recommend(MEMBER_ID, 4)).extracting(CartRecommendationResponse::productId)
                .containsExactly(305L);
    }

    @Test
    @DisplayName("거른 뒤 size개까지만, 같은 상품이 두 번 오면 한 번만")
    void limitsToSizeAndDeduplicates() {
        givenCartAndPurchased(List.of(101L), List.of());
        when(cartRecommendationQuery.recommendForCart(anyList(), anyList(), anyInt())).thenReturn(List.of(
                new Recommendation(201L, "A"), new Recommendation(201L, "A 중복"),
                new Recommendation(202L, "B"), new Recommendation(203L, "C")));
        when(productQuery.getProducts(List.of(201L, 202L, 203L))).thenReturn(List.of(
                product(201L, 20L, ProductStatus.ACTIVE, false),
                product(202L, 20L, ProductStatus.ACTIVE, false),
                product(203L, 20L, ProductStatus.ACTIVE, false)));

        assertThat(service.recommend(MEMBER_ID, 2)).extracting(CartRecommendationResponse::productId, CartRecommendationResponse::reason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(201L, "A"),
                        org.assertj.core.groups.Tuple.tuple(202L, "B"));
    }

    @Test
    @DisplayName("ai 요청 개수는 size의 2배, 최대 20")
    void aiSizeIsCapped() {
        givenCartAndPurchased(List.of(101L), List.of());
        when(cartRecommendationQuery.recommendForCart(anyList(), anyList(), anyInt())).thenReturn(List.of());

        service.recommend(MEMBER_ID, 10);

        verify(cartRecommendationQuery).recommendForCart(List.of(101L), List.of(), 20);
    }

    @Test
    @DisplayName("장바구니도 최근 구매도 없으면 ai를 부르지 않고 빈 목록")
    void noBasisSkipsAi() {
        givenCartAndPurchased(List.of(), List.of());

        assertThat(service.recommend(MEMBER_ID, 4)).isEmpty();
        verifyNoInteractions(cartRecommendationQuery, productQuery);
    }

    @Test
    @DisplayName("ai가 빈 목록(실패 · 타임아웃 포함)이면 product를 부르지 않고 빈 목록")
    void emptyAiSkipsProduct() {
        givenCartAndPurchased(List.of(101L), List.of());
        when(cartRecommendationQuery.recommendForCart(anyList(), anyList(), anyInt())).thenReturn(List.of());

        assertThat(service.recommend(MEMBER_ID, 4)).isEmpty();
        verifyNoInteractions(productQuery);
    }

    @Test
    @DisplayName("product 조회가 실패해도 추천 영역만 비운다")
    void productFailureIsEmpty() {
        givenCartAndPurchased(List.of(101L), List.of());
        when(cartRecommendationQuery.recommendForCart(anyList(), anyList(), anyInt()))
                .thenReturn(List.of(new Recommendation(201L, "A")));
        when(productQuery.getProducts(anyList())).thenThrow(new InternalApiUnavailableException("product", null));

        assertThat(service.recommend(MEMBER_ID, 4)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 11})
    @DisplayName("size는 1~10, 벗어나면 400")
    void rejectsInvalidSize(int size) {
        assertThatThrownBy(() -> service.recommend(MEMBER_ID, size)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(cartItemRepository, orderQuery, cartRecommendationQuery, productQuery);
    }

    private void givenCartAndPurchased(List<Long> cart, List<Long> purchased) {
        when(cartItemRepository.findProductIdsByMemberId(MEMBER_ID)).thenReturn(cart);
        when(orderQuery.getRecentConfirmedProductIds(eq(MEMBER_ID), any(LocalDateTime.class), eq(20)))
                .thenReturn(purchased);
    }

    private static ProductResult product(long productId, long sellerId, ProductStatus status, boolean hidden) {
        return new ProductResult(productId, sellerId, "상품 " + productId, BigDecimal.valueOf(10_000), status, hidden,
                "https://img/" + productId, "몬스테라");
    }
}
