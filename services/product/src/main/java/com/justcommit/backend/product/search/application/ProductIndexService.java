package com.justcommit.backend.product.search.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.exception.ProductErrorCode;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
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
    // 무중단 재색인은 한 번에 하나만 (같은 서버 안에서)
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

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
        return reindex(searchIndex::bulk);
    }

    /**
     * 무중단 재색인 (#487): 매핑 · 분석기를 바꿨거나 별칭 · 학명 데이터가 바뀌었을 때 검색을 멈추지 않고 인덱스를 새로 만든다.
     *
     * <ol>
     *   <li>지금 매핑으로 다음 번호의 인덱스(products_v{n+1})를 alias 없이 만든다. 그동안 검색 · 이벤트 색인은 옛 인덱스로 계속 간다.</li>
     *   <li>DB 전체를 새 인덱스에 채운다. 실패한 건이 있으면 새 인덱스를 지우고 멈춘다 (alias는 그대로라 검색에 영향 없음).</li>
     *   <li>새 인덱스를 refresh해 채운 문서가 모두 검색되게 한 뒤, alias를 한 요청으로 새 인덱스로 옮긴다
     *       (refresh 전에 옮기면 최대 1초 동안 일부만 검색됨).</li>
     *   <li>채우는 동안 옛 인덱스로 간 변경을 맞추려고 alias(이제 새 인덱스)에 전체 색인을 한 번 더 한다.
     *       외부 버전이라 이미 같은 버전인 문서는 건너뛰고, 그사이 바뀐 상품만 반영된다.</li>
     *   <li>옛 인덱스를 지운다. 실패해도 검색에는 영향이 없어 경고만 남긴다.</li>
     * </ol>
     */
    public RebuildResult rebuild() {
        if (!rebuilding.compareAndSet(false, true)) {
            throw new BusinessException(ProductErrorCode.SEARCH_REBUILD_IN_PROGRESS);
        }
        try {
            String previous = searchIndex.currentIndex();
            String next = searchIndex.createNextIndex(previous);
            log.info("상품 검색 무중단 재색인 시작: {} → {}", previous, next);

            ReindexResult filled;
            try {
                filled = reindex(writes -> searchIndex.bulkInto(next, writes));
            } catch (RuntimeException e) {
                discard(next);
                throw e;
            }
            if (filled.failed() > 0) {
                discard(next);
                throw new IllegalStateException("새 인덱스 " + next + "를 채우다 " + filled.failed()
                        + "건이 실패해 재색인을 멈춤 (alias는 " + previous + " 그대로, 로그 확인 후 다시 실행)");
            }

            searchIndex.refresh(next);
            searchIndex.switchAlias(previous, next);
            log.info("상품 검색 alias를 {}에서 {}로 옮김", previous, next);
            ReindexResult caughtUp = reindexAll();

            boolean previousDeleted = true;
            try {
                searchIndex.deleteIndex(previous);
            } catch (RuntimeException e) {
                previousDeleted = false;
                log.warn("옛 상품 검색 인덱스 {}를 지우지 못함 (검색에는 영향 없음, 직접 지워야 함)", previous, e);
            }
            RebuildResult result = new RebuildResult(previous, next, filled, caughtUp, previousDeleted);
            log.info("상품 검색 무중단 재색인 끝: {}", result);
            return result;
        } finally {
            rebuilding.set(false);
        }
    }

    // 다 채우지 못한 새 인덱스는 지운다 (다음 재색인이 같은 이름으로 다시 만들 수 있게)
    private void discard(String index) {
        try {
            searchIndex.deleteIndex(index);
        } catch (RuntimeException e) {
            log.warn("채우지 못한 상품 검색 인덱스 {}를 지우지 못함 (다음 재색인 전에 직접 지워야 함)", index, e);
        }
    }

    // DB의 모든 상품을 id 순서로 BATCH_SIZE개씩 읽어 writer로 보낸다 (alias 또는 재색인 중인 새 인덱스)
    private ReindexResult reindex(Function<List<Write>, BulkResult> writer) {
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
            int batchDeleted = 0;
            for (Product product : products) {
                if (product.getStatus() == ProductStatus.DELETED) {
                    writes.add(Write.delete(product.getId(), product.getVersion()));
                    batchDeleted++;
                } else {
                    ProductDocument document = ProductDocument.of(product,
                            aliases.getOrDefault(product.getSpecies().getId(), List.of()));
                    writes.add(Write.save(document, product.getVersion()));
                }
            }
            BulkResult result;
            try {
                result = writer.apply(writes);
            } catch (RuntimeException e) {
                // 중간 배치에서 연결이 끊기면 그때까지 끝낸 범위를 남긴다 (멱등이라 처음부터 다시 실행하면 됨)
                log.error("상품 전체 색인 중단: 끝낸 마지막 productId={}, 지금까지 indexed={}, deleted={}, skipped={}, failed={}",
                        lastId, indexed, deleted, skipped, failed, e);
                throw e;
            }
            indexed += writes.size() - batchDeleted;
            deleted += batchDeleted;
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

    /**
     * 무중단 재색인 결과. filled는 새 인덱스를 채운 결과, caughtUp은 alias를 옮긴 뒤 그사이 바뀐 상품을 맞춘 결과
     * (대부분 skipped), previousDeleted가 false면 옛 인덱스를 직접 지워야 함
     */
    public record RebuildResult(String previousIndex, String currentIndex, ReindexResult filled,
                                ReindexResult caughtUp, boolean previousDeleted) {
    }
}
