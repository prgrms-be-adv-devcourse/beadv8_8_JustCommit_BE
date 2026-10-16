package com.justcommit.backend.product.product.infrastructure;

import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.species.domain.enums.Difficulty;
import com.justcommit.backend.product.species.domain.enums.Light;
import com.justcommit.backend.product.species.domain.enums.SpeciesCategory;
import com.justcommit.backend.product.species.domain.enums.Watering;
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
            BigDecimal minPrice, BigDecimal maxPrice, Boolean onSale)  {
        List<Specification<Product>> specs = new ArrayList<>();

        // 항상 적용: 삭제·숨김 상품 제외
        specs.add((root, query, cb) -> cb.notEqual(root.get("status"), ProductStatus.DELETED));
        specs.add((root, query, cb) -> cb.isFalse(root.get("hidden")));

        // 제목 또는 품종명에 키워드가 포함된 상품
        if (StringUtils.hasText(keyword)) {
            String pattern = "%" + keyword.trim() + "%";
            specs.add((root, query, cb) -> cb.or(
                    cb.like(root.get("title"), pattern),
                    cb.like(root.get("species").get("name"), pattern)));
        }

        if (speciesId != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("species").get("id"), speciesId));
        }
        if (category != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("species").get("category"), category));
        }

        if (light != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("species").get("light"), light));
        }
        if (watering != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("species").get("watering"), watering));
        }
        if (difficulty != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("species").get("difficulty"), difficulty));
        }
        if (minPrice != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"), minPrice));
        }
        if (maxPrice != null) {
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), maxPrice));
        }

        if (Boolean.TRUE.equals(onSale)) {
            specs.add((root, query, cb) -> cb.equal(root.get("status"), ProductStatus.ACTIVE));
        }

        return Specification.allOf(specs);
    }

    // 삭제 제외. 숨김은 hidden 값으로 필터 (null 은 전체)
    public static Specification<Product> sellerCondition(Long sellerId, ProductStatus status, Boolean hidden){
        List<Specification<Product>> specs = new ArrayList<>();

        specs.add((root, query, cb) -> cb.equal(root.get("sellerId"), sellerId));
        specs.add((root, query, cb) -> cb.notEqual(root.get("status"), ProductStatus.DELETED)); // 삭제 제외

        // 삭제 지외
        if(status != null && status != ProductStatus.DELETED){
            specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }

        // hidden 이 null 이 아닌 경우 필터 추가
        if(hidden != null){
            specs.add((root, query, cb) -> cb.equal(root.get("hidden"), hidden));
        }

        return Specification.allOf(specs);
    }
}