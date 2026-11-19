package com.justcommit.backend.product.product.presentation.dto;

import com.justcommit.backend.common.request.PagingRequest;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.search.application.ProductSearchSort;
import com.justcommit.backend.product.species.domain.enums.Difficulty;
import com.justcommit.backend.product.species.domain.enums.Light;
import com.justcommit.backend.product.species.domain.enums.SpeciesCategory;
import com.justcommit.backend.product.species.domain.enums.Watering;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

public record ProductSearchRequest
        (  String keyword,
           Long speciesId,
           SpeciesCategory category,
           Light light,
           Watering watering,
           Difficulty difficulty,
           BigDecimal minPrice,
           BigDecimal maxPrice,
           Boolean onSale,
           // 판매자 상점: 그 판매자 상품만 (#486)
           Long sellerId,
           // 판매자 상점 탭: ACTIVE · PAYMENT_PENDING · SOLD_OUT 중 하나 이상 (그 밖이면 400).
           // 여러 개는 status=ACTIVE&status=PAYMENT_PENDING (판매 중 칸 = 판매 중 + 결제 대기)
           List<ProductStatus> status,
           // relevance(키워드가 있을 때 기본) · latest(키워드가 없을 때 기본) · priceAsc · priceDesc
           String sort,
           Integer page,
           Integer size
        ) implements PagingRequest {

    public boolean hasKeyword() {
        return StringUtils.hasText(keyword);
    }

    // OpenSearch 검색에서 쓰는 정렬. 모르는 값은 기본값으로 (지금까지처럼 400을 내지 않음)
    public ProductSearchSort searchSort() {
        return switch (sort) {
            case "priceAsc" -> ProductSearchSort.PRICE_ASC;
            case "priceDesc" -> ProductSearchSort.PRICE_DESC;
            case "latest" -> ProductSearchSort.LATEST;
            case null, default -> hasKeyword() ? ProductSearchSort.RELEVANCE : ProductSearchSort.LATEST;
        };
    }

    // DB 조회(키워드 없음 · OpenSearch 장애 대체)에서 쓰는 정렬. DB에는 관련도가 없어 relevance도 최신순
    @Override
    public Sort defaultSort() {

        return switch (sort) {
            case "priceAsc" -> Sort.by(Sort.Direction.ASC, "price");
            case "priceDesc" -> Sort.by(Sort.Direction.DESC, "price");
            case null, default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
    }
}
