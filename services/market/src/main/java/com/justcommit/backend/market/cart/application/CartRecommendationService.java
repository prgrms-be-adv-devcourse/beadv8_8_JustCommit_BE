package com.justcommit.backend.market.cart.application;

import com.justcommit.backend.common.client.exception.InternalApiException;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.cart.infrastructure.CartItemRepository;
import com.justcommit.backend.market.cart.presentation.CartRecommendationResponse;
import com.justcommit.backend.market.client.ai.CartRecommendationQuery;
import com.justcommit.backend.market.client.ai.CartRecommendationQuery.Recommendation;
import com.justcommit.backend.market.client.product.ProductQuery;
import com.justcommit.backend.market.client.product.ProductResult;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

// 트랜잭션을 걸지 않는다: ai 응답(최대 2초)을 기다리는 동안 DB 연결을 잡지 않게, 조회는 리포지토리 호출마다 끝냄
@Slf4j
@Service
@RequiredArgsConstructor
public class CartRecommendationService {

    public static final int DEFAULT_SIZE = 4;
    static final int MAX_SIZE = 10;
    // 최근 구매 확정 상품: 최근 90일, 최신순 최대 20개 (docs/reference/internal-apis.md)
    static final int PURCHASED_DAYS = 90;
    static final int PURCHASED_LIMIT = 20;
    // market이 본인 상품 · 구매 불가 상품을 빼면 모자랄 수 있어 ai에는 size의 2배(최대 20)를 요청
    static final int AI_SIZE_MULTIPLIER = 2;
    static final int AI_MAX_SIZE = 20;

    private final CartItemRepository cartItemRepository;
    private final OrderQuery orderQuery;
    private final CartRecommendationQuery cartRecommendationQuery;
    private final ProductQuery productQuery;

    public List<CartRecommendationResponse> recommend(long memberId, int size) {
        validateSize(size);

        List<Long> cartProductIds = cartItemRepository.findProductIdsByMemberId(memberId);
        List<Long> purchasedProductIds = orderQuery.getRecentConfirmedProductIds(
                memberId, LocalDateTime.now().minusDays(PURCHASED_DAYS), PURCHASED_LIMIT);
        if (cartProductIds.isEmpty() && purchasedProductIds.isEmpty()) {
            return List.of();
        }

        Set<Long> cartIds = new HashSet<>(cartProductIds);
        // 장바구니 상품은 ai도 빼지만 한 번 더 거르고, 같은 상품이 두 번 오면 처음 것만 (ai가 준 순서 유지)
        List<Recommendation> recommendations = List.copyOf(cartRecommendationQuery.recommendForCart(
                        cartProductIds, purchasedProductIds, Math.min(size * AI_SIZE_MULTIPLIER, AI_MAX_SIZE))
                .stream()
                .filter(r -> !cartIds.contains(r.productId()))
                .collect(Collectors.toMap(Recommendation::productId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new))
                .values());
        if (recommendations.isEmpty()) {
            return List.of();
        }

        Map<Long, ProductResult> products = findProducts(recommendations.stream().map(Recommendation::productId).toList());
        // ai가 준 순서대로, 본인 상품 · 구매 불가 상품 · product 응답에 없는 상품을 빼고 size개까지
        return recommendations.stream()
                .filter(r -> isRecommendable(products.get(r.productId()), memberId))
                .limit(size)
                .map(r -> toResponse(products.get(r.productId()), r.reason()))
                .toList();
    }

    private static void validateSize(int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST_DATA, "size는 1 이상 " + MAX_SIZE + " 이하로 입력해 주세요.");
        }
    }

    // 추천 영역만 비우고 화면은 그대로 보이게, product 조회 실패도 빈 목록으로 (조회는 fallback, resilience.md)
    private Map<Long, ProductResult> findProducts(List<Long> productIds) {
        try {
            return productQuery.getProducts(productIds).stream()
                    .collect(Collectors.toMap(ProductResult::productId, Function.identity(), (a, b) -> a));
        } catch (InternalApiException | RestClientException e) {
            log.warn("추천 상품 정보 조회 실패, 빈 목록으로 응답: count={}", productIds.size(), e);
            return Map.of();
        }
    }

    private static boolean isRecommendable(ProductResult product, long memberId) {
        return product != null && product.isPurchasable()
                && product.sellerId() != null && !product.sellerId().equals(memberId);
    }

    private static CartRecommendationResponse toResponse(ProductResult product, String reason) {
        return new CartRecommendationResponse(product.productId(), product.title(), product.price(),
                product.thumbnailUrl(), product.speciesName(), reason);
    }
}
