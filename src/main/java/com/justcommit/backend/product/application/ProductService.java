package com.justcommit.backend.product.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.ProductQuery;
import com.justcommit.backend.product.ProductResult;
import com.justcommit.backend.product.ProductUseCase;
import com.justcommit.backend.product.domain.Product;
import com.justcommit.backend.product.domain.exception.ProductErrorCode;
import com.justcommit.backend.product.infrastructure.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService implements ProductQuery, ProductUseCase {

    private final ProductRepository productRepository;

    // ── ProductQuery ───────────────────────────────

    @Override
    public ProductResult getProduct(Long productId) {
        return productRepository.findById(productId)
                .map(this::toResult)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Override
    public List<ProductResult> getProducts(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        return productRepository.findAllById(productIds).stream()
                .map(this::toResult)
                .toList();
    }

    // ── ProductUseCase ─────────────────────────────

    @Override
    @Transactional
    public boolean reserve(Long orderId, List<Long> productIds) {
        List<Long> ids = productIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            return false;
        }
        int updated = productRepository.reserve(orderId, ids);
        if (updated != ids.size()) {
            productRepository.release(orderId);   // 일부만 예약된 것을 되돌림
            return false;
        }
        return true;
    }

    @Override
    @Transactional
    public boolean release(Long orderId) {
        return productRepository.release(orderId) > 0;
    }

    @Override
    @Transactional
    public boolean markSoldOut(Long orderId) {
        return productRepository.markSoldOut(orderId) > 0;
    }

    private ProductResult toResult(Product product) {
        return new ProductResult(
                product.getId(),
                product.getSellerId(),
                product.getTitle(),
                product.getPrice(),
                product.getStatus(),
                product.isHidden(),
                product.getThumbnailUrl());
    }
}