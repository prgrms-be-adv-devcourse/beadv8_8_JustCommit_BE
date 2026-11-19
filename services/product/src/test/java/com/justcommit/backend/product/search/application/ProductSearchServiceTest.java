package com.justcommit.backend.product.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.product.presentation.dto.ProductSearchRequest;
import com.justcommit.backend.product.product.presentation.dto.ProductSearchResponse;
import com.justcommit.backend.product.species.domain.Species;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

// 상품 목록 조회의 분기: 키워드 없음 → DB, 키워드 → OpenSearch + DB 재조회, 장애 → LIKE (#486)
@ExtendWith(MockitoExtension.class)
class ProductSearchServiceTest {

    @Mock
    ProductRepository productRepository;

    @Mock
    ProductKeywordSearch keywordSearch;

    ProductSearchService service;

    @BeforeEach
    void setUp() {
        // DB 부분을 감싸는 트랜잭션은 이 테스트의 관심 밖이라 아무것도 하지 않는 매니저로
        PlatformTransactionManager noTx = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
        service = new ProductSearchService(productRepository, keywordSearch, noTx);
    }

    @Test
    @DisplayName("키워드가 없으면 OpenSearch를 부르지 않고 DB에서 조회")
    void noKeywordUsesDb() {
        given(productRepository.findAll(any(Specification.class), any(Pageable.class))).willReturn(Page.empty());

        service.search(request(null, null, null, null, null));

        verifyNoInteractions(keywordSearch);
        verify(productRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("키워드가 있으면 OpenSearch 순서대로 DB 최신 값을 담고, 그사이 숨김 · 검수 중이 된 상품은 뺀다")
    void keywordUsesOpenSearchOrderAndDbValues() {
        given(keywordSearch.search(any(), eq(ProductSearchSort.RELEVANCE), eq(0), eq(20)))
                .willReturn(new ProductKeywordSearch.Result(List.of(3L, 1L, 2L, 4L), 4));
        Product first = product(1L, ProductStatus.ACTIVE, false);
        Product hidden = product(2L, ProductStatus.ACTIVE, true);
        Product top = product(3L, ProductStatus.SOLD_OUT, false);
        Product reviewing = product(4L, ProductStatus.REVIEW_PENDING, false);
        // DB는 순서를 보장하지 않음
        given(productRepository.findWithSpeciesByIdIn(List.of(3L, 1L, 2L, 4L)))
                .willReturn(List.of(first, hidden, top, reviewing));

        Page<ProductSearchResponse> page = service.search(request("몬스테라", null, null, null, null));

        assertThat(page.getContent()).extracting(ProductSearchResponse::productId).containsExactly(3L, 1L);
        assertThat(page.getContent().getFirst().sellerId()).isEqualTo(7L);
        // 마지막 페이지에서 2개를 걸러 내 전체 개수도 실제로 보인 2개로 맞춰짐 (PageImpl)
        assertThat(page.getTotalElements()).isEqualTo(2);
        verify(productRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("OpenSearch를 쓸 수 없으면 같은 요청을 DB LIKE 검색으로 대신 응답")
    void fallsBackToLikeWhenOpenSearchUnavailable() {
        given(keywordSearch.search(any(), any(), anyInt(), anyInt()))
                .willThrow(new ProductSearchUnavailableException("연결 실패", new java.net.ConnectException()));
        Product product = product(1L, ProductStatus.ACTIVE, false);
        given(productRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(product), PageRequest.of(0, 20), 1));

        Page<ProductSearchResponse> page = service.search(request("몬스테라", null, null, null, null));

        assertThat(page.getContent()).extracting(ProductSearchResponse::productId).containsExactly(1L);
    }

    @Test
    @DisplayName("검색 노출 상태 밖의 status는 400 INVALID_SEARCH_STATUS (OpenSearch · DB 모두 부르지 않음)")
    void rejectsNonSearchableStatus() {
        for (ProductStatus status : List.of(ProductStatus.REVIEW_PENDING, ProductStatus.REJECTED, ProductStatus.DELETED)) {
            assertThatThrownBy(() -> service.search(request("몬스테라", null, null, status, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProductErrorCode.INVALID_SEARCH_STATUS);
        }
        verifyNoInteractions(keywordSearch, productRepository);
    }

    @Test
    @DisplayName("노출 상태: 기본 3개, onSale=true면 ACTIVE만, status=SOLD_OUT이면 그 상태만 · sellerId도 넘김")
    void statusesAndSellerPassedToOpenSearch() {
        given(keywordSearch.search(any(), any(), anyInt(), anyInt()))
                .willReturn(new ProductKeywordSearch.Result(List.of(), 0));
        ArgumentCaptor<ProductKeywordSearch.Condition> captor = ArgumentCaptor.forClass(ProductKeywordSearch.Condition.class);

        service.search(request("몬스테라", null, null, null, null));
        service.search(request("몬스테라", true, null, null, null));
        service.search(request("몬스테라", null, 7L, ProductStatus.SOLD_OUT, null));

        verify(keywordSearch, org.mockito.Mockito.times(3)).search(captor.capture(), any(), anyInt(), anyInt());
        List<ProductKeywordSearch.Condition> conditions = captor.getAllValues();
        assertThat(conditions.get(0).statuses())
                .containsExactly(ProductStatus.ACTIVE, ProductStatus.PAYMENT_PENDING, ProductStatus.SOLD_OUT);
        assertThat(conditions.get(1).statuses()).containsExactly(ProductStatus.ACTIVE);
        assertThat(conditions.get(2).statuses()).containsExactly(ProductStatus.SOLD_OUT);
        assertThat(conditions.get(2).sellerId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("정렬: 키워드가 있으면 관련도 기본, 없으면 최신 기본, priceAsc · priceDesc · latest는 그대로")
    void searchSort() {
        assertThat(request("몬스테라", null, null, null, null).searchSort()).isEqualTo(ProductSearchSort.RELEVANCE);
        assertThat(request(null, null, null, null, null).searchSort()).isEqualTo(ProductSearchSort.LATEST);
        assertThat(request(null, null, null, null, "relevance").searchSort()).isEqualTo(ProductSearchSort.LATEST);
        assertThat(request("몬스테라", null, null, null, "latest").searchSort()).isEqualTo(ProductSearchSort.LATEST);
        assertThat(request("몬스테라", null, null, null, "priceAsc").searchSort()).isEqualTo(ProductSearchSort.PRICE_ASC);
        assertThat(request("몬스테라", null, null, null, "priceDesc").searchSort()).isEqualTo(ProductSearchSort.PRICE_DESC);
    }

    @Test
    @DisplayName("페이지 크기: 기본 20, 최대 50")
    void pageSize() {
        given(keywordSearch.search(any(), any(), anyInt(), anyInt()))
                .willReturn(new ProductKeywordSearch.Result(List.of(), 0));

        service.search(request("몬스테라", null, null, null, null));
        service.search(new ProductSearchRequest("몬스테라", null, null, null, null, null, null, null, null,
                null, null, null, 2, 100));

        verify(keywordSearch).search(any(), any(), eq(0), eq(20));
        verify(keywordSearch).search(any(), any(), eq(2), eq(50));
    }

    private static ProductSearchRequest request(String keyword, Boolean onSale, Long sellerId, ProductStatus status,
                                                String sort) {
        return new ProductSearchRequest(keyword, null, null, null, null, null, null, null, onSale, sellerId, status,
                sort, null, null);
    }

    private static Product product(Long id, ProductStatus status, boolean hidden) {
        Species species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "id", 62L);
        ReflectionTestUtils.setField(species, "name", "몬스테라");
        Product product = Product.create(species, 7L, "상품 " + id, "설명", new BigDecimal("10000"), null, null, null,
                List.of());
        ReflectionTestUtils.setField(product, "id", id);
        ReflectionTestUtils.setField(product, "status", status);
        ReflectionTestUtils.setField(product, "hidden", hidden);
        return product;
    }
}
