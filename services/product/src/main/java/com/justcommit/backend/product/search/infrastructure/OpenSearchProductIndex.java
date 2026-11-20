package com.justcommit.backend.product.search.infrastructure;

import com.justcommit.backend.product.search.application.ProductDocument;
import com.justcommit.backend.product.search.application.ProductSearchIndex;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.VersionType;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.transport.httpclient5.ResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 상품 문서를 alias {@code products}에 쓴다 (실제 인덱스는 products_v1, ProductIndexInitializer가 만듦).
 *
 * <p>두 가지 안전장치:
 * <ul>
 *   <li>{@code require_alias}: alias가 아직 없으면(OpenSearch가 늦게 떠서 인덱스 준비 전) 쓰기를 실패시킨다.
 *       이게 없으면 OpenSearch가 {@code products}라는 이름의 인덱스를 매핑 없이 자동으로 만들어 버린다.
 *       실패한 이벤트는 공통 소비 설정대로 1 · 2 · 4초 재시도 후 DLT로 가고, 전체 색인으로 다시 채운다.</li>
 *   <li>외부 버전({@code version_type=external}, 버전 = 상품 버전): 저장된 버전보다 클 때만 반영.
 *       같거나 작으면 409(version_conflict)인데, 이미 같은 값이나 더 새 값이 있다는 뜻이라 성공으로 본다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenSearchProductIndex implements ProductSearchIndex {

    private static final String VERSION_CONFLICT = "version_conflict_engine_exception";

    private final OpenSearchClient client;
    private final ProductIndexAdmin indexAdmin;

    @Override
    public void save(ProductDocument document, long version) {
        boolean applied = writeIgnoringVersionConflict(() -> client.index(request -> request
                .index(ProductIndexInitializer.ALIAS)
                .id(String.valueOf(document.productId()))
                .document(document)
                .versionType(VersionType.External)
                .version(version)
                .requireAlias(true)));
        if (!applied) {
            log.info("상품 문서에 같거나 더 새 버전이 이미 있어 건너뜀: productId={}, version={}",
                    document.productId(), version);
        }
    }

    @Override
    public void delete(Long productId, long version) {
        // 문서가 없어도 삭제 응답(not_found)이 오고 예외가 아님. 외부 버전이라 삭제 표시(tombstone)가 잠시 남아
        // 그사이 늦게 온 옛 버전 쓰기도 막힌다
        boolean applied = writeIgnoringVersionConflict(() -> client.delete(request -> request
                .index(ProductIndexInitializer.ALIAS)
                .id(String.valueOf(productId))
                .versionType(VersionType.External)
                .version(version)));
        if (!applied) {
            log.info("상품 문서에 같거나 더 새 버전이 이미 있어 삭제를 건너뜀: productId={}, version={}", productId, version);
        }
    }

    // 반영했으면 true, 버전 충돌(409)이면 false. 그 밖의 오류는 던져서 리스너가 재시도 · DLT로 보내게 함
    // 클라이언트가 409를 OpenSearchException이 아니라 ResponseException(IOException)으로 줄 때가 있어 둘 다 확인
    private boolean writeIgnoringVersionConflict(OpenSearchCall call) {
        try {
            call.run();
            return true;
        } catch (OpenSearchException e) {
            if (isVersionConflict(e.error().type())) {
                return false;
            }
            throw e;
        } catch (ResponseException e) {
            // 외부 버전 쓰기에서 409는 버전 충돌뿐
            if (e.status() == HttpStatus.CONFLICT.value()) {
                return false;
            }
            throw new UncheckedIOException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface OpenSearchCall {
        void run() throws IOException;
    }

    @Override
    public BulkResult bulk(List<Write> writes) {
        return bulk(ProductIndexInitializer.ALIAS, true, writes);
    }

    @Override
    public BulkResult bulkInto(String index, List<Write> writes) {
        return bulk(index, false, writes);
    }

    private BulkResult bulk(String target, boolean requireAlias, List<Write> writes) {
        if (writes.isEmpty()) {
            return new BulkResult(0, 0, 0);
        }
        List<BulkOperation> operations = writes.stream().map(OpenSearchProductIndex::toOperation).toList();
        BulkResponse response;
        try {
            response = client.bulk(request -> request
                    .index(target)
                    .requireAlias(requireAlias)
                    .operations(operations));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // bulk는 일부가 실패해도 응답은 200이라 건마다 error를 확인해야 함
        int applied = 0;
        int skipped = 0;
        int failed = 0;
        for (BulkResponseItem item : response.items()) {
            if (item.error() == null) {
                applied++;
            } else if (isVersionConflict(item.error().type())) {
                skipped++;
            } else {
                failed++;
                log.warn("전체 색인 중 상품 문서 쓰기 실패: productId={}, type={}, reason={}",
                        item.id(), item.error().type(), item.error().reason());
            }
        }
        return new BulkResult(applied, skipped, failed);
    }

    @Override
    public String currentIndex() {
        try {
            return indexAdmin.currentIndex()
                    .orElseThrow(() -> new IllegalStateException("상품 검색 alias '" + ProductIndexAdmin.ALIAS + "'가 없음"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String createNextIndex(String current) {
        String next = ProductIndexAdmin.nextIndexName(current);
        try {
            indexAdmin.create(next, false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return next;
    }

    @Override
    public void refresh(String index) {
        try {
            client.indices().refresh(request -> request.index(index));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void switchAlias(String from, String to) {
        try {
            indexAdmin.switchAlias(from, to);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void deleteIndex(String index) {
        try {
            indexAdmin.delete(index);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BulkOperation toOperation(Write write) {
        String id = String.valueOf(write.productId());
        if (write.isDelete()) {
            return BulkOperation.of(op -> op.delete(delete -> delete
                    .id(id).versionType(VersionType.External).version(write.version())));
        }
        return BulkOperation.of(op -> op.index(index -> index
                .id(id).document(write.document()).versionType(VersionType.External).version(write.version())));
    }

    private static boolean isVersionConflict(String errorType) {
        return VERSION_CONFLICT.equals(errorType);
    }
}
