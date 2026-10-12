package com.justcommit.backend.product.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.ProductQuery;
import com.justcommit.backend.product.ProductResult;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.ProductUseCase;
import com.justcommit.backend.product.domain.Product;
import com.justcommit.backend.product.domain.Species;
import com.justcommit.backend.product.domain.exception.ProductErrorCode;
import com.justcommit.backend.product.infrastructure.repository.ProductRepository;
import com.justcommit.backend.product.infrastructure.repository.ProductSpecifications;
import com.justcommit.backend.product.infrastructure.repository.SpeciesRepository;
import com.justcommit.backend.product.presentation.dto.ProductCreateRequest;
import com.justcommit.backend.product.presentation.dto.ProductDetailResponse;
import com.justcommit.backend.product.presentation.dto.ProductSearchRequest;
import com.justcommit.backend.product.presentation.dto.ProductSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService implements ProductQuery, ProductUseCase {

    private final ProductRepository productRepository;
private final SpeciesRepository speciesRepository;

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

    // 상품 API
    @Transactional
    public ProductDetailResponse createProduct(Long sellerId, ProductCreateRequest request){
        Species species = speciesRepository.findById(request.speciesId())
                .orElseThrow(() -> new BusinessException(ProductErrorCode.SPECIES_NOT_FOUND));

        List<String> pictureUrls = request.pictureUrls() == null ? List.of() : request.pictureUrls();

        Product product = Product.create(
                species, sellerId, request.title(), request.description(), request.price(),
                request.heightCm(), request.potSize(), request.growNote(), pictureUrls);

        return ProductDetailResponse.from(productRepository.save(product));
    }

    public ProductDetailResponse getProductDetail(Long productId){
        return productRepository.findWithDetailById(productId)
                .filter(product -> product.getStatus() != ProductStatus.DELETED && !product.isHidden())
                .map(ProductDetailResponse::from)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    public Page<ProductSearchResponse> searchProducts(ProductSearchRequest request) {
        return productRepository.findAll(
                        ProductSpecifications.searchCondition(
                                request.keyword(), request.speciesId(), request.category(),
                                request.light(), request.watering(), request.difficulty(),
                                request.minPrice(), request.maxPrice()),
                        request.toPageable())
                .map(ProductSearchResponse::from);
    }

}