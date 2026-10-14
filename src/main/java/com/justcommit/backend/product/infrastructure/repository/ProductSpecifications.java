package com.justcommit.backend.product.infrastructure.repository;

import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.domain.Product;
import com.justcommit.backend.product.domain.enums.Difficulty;
import com.justcommit.backend.product.domain.enums.Light;
import com.justcommit.backend.product.domain.enums.SpeciesCategory;
import com.justcommit.backend.product.domain.enums.Watering;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    public static Specification<Product> searchCondition(
            String keyword, Long speciesId, SpeciesCategory category,
            Light light, Watering watering, Difficulty difficulty,
            BigDecimal minPrice, BigDecimal maxPrice)  {
        List<Specification<Product>> specs = new ArrayList<>();

        // 항상 적용: 삭제·숨김 상품 제외
        specs.add((root, query, cb) -> cb.notEqual(root.get("status"), ProductStatus.DELETED));
        specs.add((root, query, cb) -> cb.isFalse(root.get("hidden")));

        if (StringUtils.hasText(keyword)) {
            specs.add((root, query, cb) -> cb.like(root.get("title"), "%" + keyword.trim() + "%"));
        }
        if (speciesId != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("species").get("id"), speciesId));
        }
        if (category != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("species").get("category"), category));
        }
        if (minPrice != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"), minPrice));
        }
        if (maxPrice != null) {
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), maxPrice));
        }
        return Specification.allOf(specs);
    }

    // 숨김 포함, 삭제만 제외
    public static Specification<Product> sellerCondition(Long sellerId, ProductStatus status){
        List<Specification<Product>> specs = new ArrayList<>();

        specs.add((root, query, cb) -> cb.equal(root.get("sellerId"), sellerId));
        specs.add((root, query, cb) -> cb.notEqual(root.get("status"), ProductStatus.DELETED)); // 삭제 제외

        if(status != null && status != ProductStatus.DELETED){
            specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }

        return Specification.allOf(specs);
    }
}