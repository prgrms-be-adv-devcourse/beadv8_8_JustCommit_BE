package com.justcommit.backend.product.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;

// 상품 수정·삭제로 더 이상 쓰지 않는 이미지를 DB 커밋 후 저장소에서 삭제
@Component
public class ProductImageCleaner {

    private final ImageDeleter imageDeleter;
    private final String productImagePrefix;

    public ProductImageCleaner(ImageDeleter imageDeleter, @Value("${custom.image.base-url}") String baseUrl) {
        this.imageDeleter = imageDeleter;
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.productImagePrefix = normalized + "/products/";
    }

    public void deleteAfterCommit(Collection<String> imageUrls) {
        // 우리 버킷의 상품 이미지만 키로 변환 (외부·예전 URL은 건너뜀)
        List<String> keys = imageUrls.stream()
                .filter(url -> url.startsWith(productImagePrefix))
                .map(url -> "products/" + url.substring(productImagePrefix.length()))
                .toList();
        if (keys.isEmpty()) {
            return;
        }

        // 롤백되면 실행되지 않음 → DB에는 있는데 파일만 사라지는 일이 없음
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                imageDeleter.delete(keys);
            }
        });
    }
}