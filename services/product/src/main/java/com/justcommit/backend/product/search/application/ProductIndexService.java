package com.justcommit.backend.product.search.application;

import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.search.application.ProductSearchIndex.BulkResult;
import com.justcommit.backend.product.search.application.ProductSearchIndex.Write;
import com.justcommit.backend.product.species.domain.SpeciesAlias;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * DB(원본) → OpenSearch(복사본) 동기화 (docs/flows/search-recommend.md 「검색 색인」, ADR 0005).
 *
 * <p>이벤트 payload는 믿지 않고 「이 상품이 바뀌었다」는 신호로만 쓴다. 매번 DB에서 최신 상품 · 품종명 · 별칭 · 학명을 읽어
 * 문서를 통째로 덮어쓰므로, 같은 이벤트가 두 번 오거나 순서가 바뀌어도 마지막 결과는 DB와 같다.
 *
 * <p>트랜잭션을 걸지 않는다: DB 조회는 저장소 메서드마다 짧게 끝내고, OpenSearch 호출(최대 수 초) 동안 DB 연결을 잡지 않으려고.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductIndexService {

    // 전체 색인 한 번에 읽고 보내는 상품 수 (bulk 요청 하나의 크기)
    static final int BATCH_SIZE = 500;

    private final ProductRepository productRepository;
    private final SpeciesRepository speciesRepository;
    private final ProductSearchIndex searchIndex;

    // 이벤트 한 건: 이 상품의 문서를 DB 최신 값으로 맞춤. OpenSearch 오류는 그대로 던져 공통 재시도 · DLT로 보냄
    public void sync(Long productId) {
        Optional<Product> found = productRepository.findWithSpeciesById(productId);
        if (found.isEmpty()) {
            // 상품 행은 지우지 않으므로(삭제도 DELETED 상태) 정상이라면 없음. 다시 해도 같으니 재시도하지 않고 남김
            log.warn("색인할 상품이 DB에 없어 건너뜀: productId={}", productId);
            return;
        }
        Product product = found.get();
        if (product.getStatus() == ProductStatus.DELETED) {
            searchIndex.delete(productId, product.getVersion());
            log.info("상품 문서 삭제: productId={}, version={}", productId, product.getVersion());
            return;
        }
        searchIndex.save(ProductDocument.of(product, aliasesBySpecies(List.of(product)).getOrDefault(
                product.getSpecies().getId(), List.of())), product.getVersion());
        log.info("상품 문서 색인: productId={}, status={}, hidden={}, version={}",
                productId, product.getStatus(), product.isHidden(), product.getVersion());
    }

    /**
     * 전체 색인: DB의 모든 상품을 id 순서로 BATCH_SIZE개씩 읽어 bulk로 보낸다.
     * 처음 띄울 때 · 인덱스를 다시 만들 때 · 이벤트가 DLT로 빠져 문서가 어긋났을 때 쓴다.
     * 삭제 상품은 문서를 지우고(남아 있던 문서 정리), 나머지는 덮어쓴다. 여러 번 돌려도 결과가 같다.
     */
    public ReindexResult reindexAll() {
        long lastId = 0;
        int indexed = 0;
        int deleted = 0;
        int skipped = 0;
        int failed = 0;
        while (true) {
            List<Product> products = productRepository.findByIdGreaterThanOrderByIdAsc(lastId, Limit.of(BATCH_SIZE));
            if (products.isEmpty()) {
                break;
            }
            Map<Long, List<String>> aliases = aliasesBySpecies(products);
            List<Write> writes = new ArrayList<>(products.size());
            for (Product product : products) {
                if (product.getStatus() == ProductStatus.DELETED) {
                    writes.add(Write.delete(product.getId(), product.getVersion()));
                    deleted++;
                } else {
                    ProductDocument document = ProductDocument.of(product,
                            aliases.getOrDefault(product.getSpecies().getId(), List.of()));
                    writes.add(Write.save(document, product.getVersion()));
                    indexed++;
                }
            }
            BulkResult result = searchIndex.bulk(writes);
            skipped += result.skipped();
            failed += result.failed();
            lastId = products.getLast().getId();
        }
        ReindexResult result = new ReindexResult(indexed, deleted, skipped, failed);
        log.info("상품 전체 색인 끝: {}", result);
        return result;
    }

    // 품종 id → 별칭 목록 (한 번의 쿼리로, 상품마다 별칭을 따로 읽지 않게)
    private Map<Long, List<String>> aliasesBySpecies(Collection<Product> products) {
        List<Long> speciesIds = products.stream().map(product -> product.getSpecies().getId()).distinct().toList();
        return speciesRepository.findAliasesBySpeciesIds(speciesIds).stream()
                .collect(Collectors.groupingBy(alias -> alias.getSpecies().getId(),
                        Collectors.mapping(SpeciesAlias::getAlias, Collectors.toList())));
    }

    /**
     * 전체 색인 결과. indexed · deleted는 보낸 수이고, 그중 skipped는 같거나 더 새 버전이 이미 있어 건너뛴 수,
     * failed는 OpenSearch가 거절한 수 (0이 아니면 로그를 보고 다시 실행)
     */
    public record ReindexResult(int indexed, int deleted, int skipped, int failed) {
    }
}
