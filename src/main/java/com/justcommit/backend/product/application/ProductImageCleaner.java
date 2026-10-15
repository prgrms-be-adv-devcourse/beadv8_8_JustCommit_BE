package com.justcommit.backend.product.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

// 상품 수정·삭제로 더 이상 쓰지 않는 이미지를 DB 커밋 후 저장소에서 삭제
@Component
public class ProductImageCleaner {

    private final ImageDeleter imageDeleter;
    private final ProductImageUrls imageUrls;

    public ProductImageCleaner(ImageDeleter imageDeleter, ProductImageUrls imageUrls) {
        this.imageDeleter = imageDeleter;
        this.imageUrls = imageUrls;
    }

    public void deleteAfterCommit(Collection<String> urls) {
        List<String> keys = urls.stream()
                .map(imageUrls::keyFromUrl)
                .filter(Objects::nonNull)          // 외부·상품 경로가 아닌 URL은 건너뜀
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