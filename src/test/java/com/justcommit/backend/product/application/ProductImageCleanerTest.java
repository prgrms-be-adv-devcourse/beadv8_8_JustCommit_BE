package com.justcommit.backend.product.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProductImageCleanerTest {

    private static final String BASE_URL = "https://bucket.example.com";

    @Mock
    private ImageDeleter imageDeleter;

    private ProductImageCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new ProductImageCleaner(imageDeleter, BASE_URL + "/");   // 끝 '/'도 같이 확인
        TransactionSynchronizationManager.initSynchronization();          // 트랜잭션 안인 것처럼
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    @Test
    @DisplayName("커밋 후 우리 버킷의 상품 이미지를 키로 바꿔 삭제")
    void deleteAfterCommit() {
        cleaner.deleteAfterCommit(List.of(BASE_URL + "/products/7/a.jpg", BASE_URL + "/products/7/b.png"));

        verify(imageDeleter, never()).delete(any());   // 커밋 전에는 삭제 안 함
        commit();
        verify(imageDeleter).delete(List.of("products/7/a.jpg", "products/7/b.png"));
    }

    @Test
    @DisplayName("외부 URL이나 상품 경로가 아닌 이미지는 삭제하지 않음")
    void skipsForeignUrls() {
        cleaner.deleteAfterCommit(List.of("https://example.com/a.jpg", BASE_URL + "/species/1/a.jpg"));

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    @DisplayName("롤백되면 삭제하지 않음")
    void doesNotDeleteOnRollback() {
        cleaner.deleteAfterCommit(List.of(BASE_URL + "/products/7/a.jpg"));

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(imageDeleter, never()).delete(any());
    }
}