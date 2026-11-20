package com.justcommit.backend.ai.product.application;

import com.justcommit.backend.ai.product.infrastructure.ProductSnapshotRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 상품 재임베딩 운영 API (target=PRODUCT): 문서 값이 있는 상품을 모두 맞춘다. 문서가 그대로면 임베딩 모델을 부르지 않음
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductReindexService {

    private final ProductSnapshotRepository snapshotRepository;
    private final ProductDocumentIndexer indexer;

    public record ProductReindexResult(int total, int embedded, int skipped) {
    }

    public ProductReindexResult reindex() {
        List<Long> ids = snapshotRepository.findIdsWithDocument();
        int embedded = 0;
        for (Long id : ids) {
            if (indexer.sync(id) == ProductDocumentIndexer.Result.EMBEDDED) {
                embedded++;
            }
        }
        ProductReindexResult result = new ProductReindexResult(ids.size(), embedded, ids.size() - embedded);
        log.info("상품 재임베딩 완료: {}", result);
        return result;
    }
}
