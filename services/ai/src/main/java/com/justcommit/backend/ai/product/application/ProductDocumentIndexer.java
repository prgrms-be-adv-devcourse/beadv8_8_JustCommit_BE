package com.justcommit.backend.ai.product.application;

import com.justcommit.backend.ai.product.application.ProductDocumentFactory.ProductDocument;
import com.justcommit.backend.ai.product.domain.ProductSnapshot;
import com.justcommit.backend.ai.product.infrastructure.ProductSnapshotRepository;
import com.justcommit.backend.ai.product.infrastructure.ProductVectorRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 상품 문서 임베딩 (#298): 저장된 스냅샷(최신 버전)으로 상품 문서를 만들어 product_vector_store를 맞춘다.
 * 이벤트 payload가 아니라 저장된 스냅샷을 읽으므로, 늦게 온 옛 이벤트 · 재시도로 다시 불러도 결과가 같다.
 * - 삭제(DELETED): 벡터 삭제
 * - 판매 완료 · 숨김 등: 벡터는 두고 검색할 때 스냅샷과 조인해 거름 (문서 해시가 같아 임베딩하지 않음)
 * - payload v2로 저장돼 제목이 없는 상품: 건너뜀 (다음 v3 이벤트나 재발행 때 임베딩)
 */
@Slf4j
@Service
public class ProductDocumentIndexer {

    public enum Result { EMBEDDED, SKIPPED, DELETED }

    private final ProductSnapshotRepository snapshotRepository;
    private final ProductVectorRepository vectorRepository;
    private final ProductDocumentFactory documentFactory;
    private final PgVectorStore productVectorStore;

    public ProductDocumentIndexer(
            ProductSnapshotRepository snapshotRepository,
            ProductVectorRepository vectorRepository,
            ProductDocumentFactory documentFactory,
            @Qualifier("productVectorStore") PgVectorStore productVectorStore) {
        this.snapshotRepository = snapshotRepository;
        this.vectorRepository = vectorRepository;
        this.documentFactory = documentFactory;
        this.productVectorStore = productVectorStore;
    }

    /** 임베딩 API가 실패하면 예외 그대로 (리스너는 1 · 2 · 4초 재시도 후 DLT) */
    public Result sync(long productId) {
        Optional<ProductSnapshot> found = snapshotRepository.findById(productId);
        if (found.isEmpty()) {
            return Result.SKIPPED;
        }
        ProductSnapshot snapshot = found.get();
        Optional<String> storedHash = vectorRepository.findDocumentHash(productId);
        if (ProductSnapshot.DELETED.equals(snapshot.status())) {
            if (storedHash.isPresent()) {
                productVectorStore.delete(List.of(String.valueOf(productId)));
                return Result.DELETED;
            }
            return Result.SKIPPED;
        }
        if (!snapshot.hasDocument()) {
            return Result.SKIPPED;
        }
        ProductDocument document = documentFactory.create(snapshot,
                vectorRepository.findSpeciesSummary(snapshot.speciesId()).orElse(null));
        if (Objects.equals(storedHash.orElse(null), document.hash())) {
            return Result.SKIPPED;
        }
        productVectorStore.add(List.of(Document.builder()
                .id(String.valueOf(productId))
                .text(document.text())
                .metadata(Map.of(
                        "productId", productId,
                        "speciesId", snapshot.speciesId(),
                        "documentHash", document.hash()))
                .build()));
        log.debug("상품 문서 임베딩: productId={}", productId);
        return Result.EMBEDDED;
    }
}
