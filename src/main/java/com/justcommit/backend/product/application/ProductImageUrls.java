package com.justcommit.backend.product.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 상품 이미지 URL 규칙 (기본 URL + products/{sellerId}/{파일})
@Component
public class ProductImageUrls {

    private static final String PRODUCT_DIR = "products/";

    private final String baseUrl;

    public ProductImageUrls(@Value("${custom.image.base-url}") String baseUrl) {
        // 설정값 끝에 '/'가 있어도 없어도 같게 처리
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    // 업로드할 키: products/{sellerId}/{fileName}
    public String keyOf(Long sellerId, String fileName) {
        return PRODUCT_DIR + sellerId + "/" + fileName;
    }

    // 키 → 최종 이미지 URL
    public String urlOf(String key) {
        return baseUrl + "/" + key;
    }

    // 이 판매자 경로의 이미지인지 (검증용)
    public boolean isOwnedBy(Long sellerId, String url) {
        return url.startsWith(urlOf(PRODUCT_DIR + sellerId + "/")) && !url.contains("..");
    }

    // 우리 버킷의 상품 이미지면 키, 아니면 null (삭제용)
    public String keyFromUrl(String url) {
        String prefix = urlOf(PRODUCT_DIR);
        return url.startsWith(prefix) ? PRODUCT_DIR + url.substring(prefix.length()) : null;
    }
}