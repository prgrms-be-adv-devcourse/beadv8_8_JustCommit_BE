package com.justcommit.backend.product.search.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.product.infrastructure.ProductSpecifications;
import com.justcommit.backend.product.product.presentation.dto.ProductSearchRequest;
import com.justcommit.backend.product.product.presentation.dto.ProductSearchResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 상품 목록 조회 {@code GET /api/v1/products} (docs/flows/search-recommend.md 「검색 색인」 표, #486).
 *
 * <ul>
 *   <li>키워드 없음 → DB 조회 (Specifications). 원본이라 색인 지연이 없고 전체 개수가 정확하다.</li>
 *   <li>판매자 상점({@code sellerId}) → 키워드가 있어도 DB 조회. 시안에 상점 안 검색창이 없어 OpenSearch 문서에
 *       판매자를 색인하지 않는다 (키워드가 오면 LIKE로 거름).</li>
 *   <li>키워드 있음 → OpenSearch로 id · 전체 개수를 받고, 화면 값은 DB에서 다시 읽는다 (응답 형식이 DB 조회와 같음).</li>
 *   <li>OpenSearch 장애 → 경고 로그를 남기고 DB LIKE 검색으로 대신 (관련도 · 한국어 분석 없음, 최신순).</li>
 * </ul>
 *
 * <p>메서드 전체에 트랜잭션을 걸지 않는다: OpenSearch를 기다리는 동안(최대 수 초) DB 연결을 잡지 않으려고.
 * DB를 읽고 응답으로 바꾸는 부분만 읽기 전용 트랜잭션으로 감싼다 (open-in-view가 꺼져 있어, 썸네일용 사진 목록을
 * 지연 로딩하려면 세션이 열려 있어야 함).
 */
@Slf4j
@Service
public class ProductSearchService {

    private final ProductRepository productRepository;
    private final ProductKeywordSearch keywordSearch;
    private final TransactionTemplate readOnly;

    public ProductSearchService(ProductRepository productRepository, ProductKeywordSearch keywordSearch,
                                PlatformTransactionManager transactionManager) {
        this.productRepository = productRepository;
        this.keywordSearch = keywordSearch;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    public Page<ProductSearchResponse> search(ProductSearchRequest request) {
        List<ProductStatus> statuses = statusesOf(request);
        Pageable pageable = request.toPageable();
        if (!request.hasKeyword() || request.sellerId() != null) {
            return searchDb(request, pageable);
        }
        try {
            return searchOpenSearch(request, statuses, pageable);
        } catch (ProductSearchUnavailableException e) {
            log.warn("OpenSearch 검색 실패, DB LIKE 검색으로 대신함: keyword={}, cause={}",
                    request.keyword(), e.getCause() == null ? e.toString() : e.getCause().toString());
            return searchDb(request, pageable);
        }
    }

    /**
     * 검색 노출 규칙으로 볼 상태: ACTIVE · PAYMENT_PENDING · SOLD_OUT. onSale=true면 ACTIVE만,
     * status를 주면 그 상태만 (둘 다 주면 둘 다 만족해야 함). 노출 상태 밖의 status는 400.
     */
    private static List<ProductStatus> statusesOf(ProductSearchRequest request) {
        ProductStatus status = request.status();
        if (status != null && !ProductSpecifications.SEARCHABLE_STATUSES.contains(status)) {
            throw new BusinessException(ProductErrorCode.INVALID_SEARCH_STATUS);
        }
        return ProductSpecifications.SEARCHABLE_STATUSES.stream()
                .filter(s -> !Boolean.TRUE.equals(request.onSale()) || s == ProductStatus.ACTIVE)
                .filter(s -> status == null || s == status)
                .toList();
    }

    private Page<ProductSearchResponse> searchDb(ProductSearchRequest request, Pageable pageable) {
        return readOnly.execute(status -> productRepository.findAll(
                        ProductSpecifications.searchCondition(
                                request.keyword(), request.speciesId(), request.category(),
                                request.light(), request.watering(), request.difficulty(),
                                request.minPrice(), request.maxPrice(), request.onSale(),
                                request.sellerId(), request.status()),
                        pageable)
                .map(ProductSearchResponse::from));
    }

    private Page<ProductSearchResponse> searchOpenSearch(ProductSearchRequest request, List<ProductStatus> statuses,
                                                         Pageable pageable) {
        ProductKeywordSearch.Condition condition = new ProductKeywordSearch.Condition(
                request.keyword().trim(), statuses, request.speciesId(),
                nameOf(request.category()), nameOf(request.light()), nameOf(request.watering()),
                nameOf(request.difficulty()), request.minPrice(), request.maxPrice());
        ProductKeywordSearch.Result result = keywordSearch.search(
                condition, request.searchSort(), pageable.getPageNumber(), pageable.getPageSize());

        // OpenSearch가 정한 순서를 지키며 DB 최신 값으로. 색인이 몇 초 늦어 그사이 숨김 · 판매 상태가 바뀐 상품은
        // 여기서 한 번 더 걸러 내 화면에 나가지 않게 한다 (전체 개수는 OpenSearch 기준이라 그 몇 초 동안 1~2개 차이 날 수 있음)
        List<ProductSearchResponse> content = readOnly.execute(status -> {
            Map<Long, Product> products = productRepository.findWithSpeciesByIdIn(result.productIds()).stream()
                    .collect(Collectors.toMap(Product::getId, Function.identity()));
            return result.productIds().stream()
                    .map(products::get)
                    .filter(Objects::nonNull)
                    .filter(product -> !product.isHidden() && statuses.contains(product.getStatus()))
                    .map(ProductSearchResponse::from)
                    .toList();
        });
        // 정렬은 OpenSearch가 했으므로 페이지 정보에는 번호 · 크기만 담음.
        // 마지막 페이지에서 걸러 낸 상품이 있으면 PageImpl이 전체 개수를 (앞 페이지 수 + 이번 개수)로 맞춘다
        return new PageImpl<>(content, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()),
                result.total());
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
