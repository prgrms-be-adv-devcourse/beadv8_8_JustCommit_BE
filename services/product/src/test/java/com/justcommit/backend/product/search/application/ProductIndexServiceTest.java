package com.justcommit.backend.product.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.search.application.ProductIndexService.RebuildResult;
import com.justcommit.backend.product.search.application.ProductSearchIndex.BulkResult;
import com.justcommit.backend.product.search.domain.SearchIndexJobPhase;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

// 무중단 재색인의 순서와 실패 처리 (#487). 잠금 · 작업 기록은 SearchIndexJobServiceTest (#563). 실제 OpenSearch로 도는 흐름은 ProductSearchIntegrationTest
// 공통 준비(DB 상품 · 인덱스 이름)를 테스트마다 다 쓰지는 않아 LENIENT
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductIndexServiceTest {

    @Mock
    ProductRepository productRepository;

    @Mock
    SpeciesRepository speciesRepository;

    @Mock
    ProductSearchIndex searchIndex;

    ProductIndexService service;

    @BeforeEach
    void setUp() {
        service = new ProductIndexService(productRepository, speciesRepository, searchIndex);
        // DB에는 상품 하나: 첫 배치에 1개, 다음 배치는 비어 끝남 (전체 색인을 두 번 돌아도 같게)
        Product product = product(1L);
        given(productRepository.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Limit.class))).willReturn(List.of(product));
        given(productRepository.findByIdGreaterThanOrderByIdAsc(eq(1L), any(Limit.class))).willReturn(List.of());
        given(speciesRepository.findAliasesBySpeciesIds(anyList())).willReturn(List.of());
        given(searchIndex.currentIndex()).willReturn("products_v1");
        // 작업 ID와 상관없이 (목 IndexProgress의 jobId()는 0을 돌려줌)
        given(searchIndex.createNextIndex(eq("products_v1"), any())).willReturn("products_v2");
    }

    @Test
    @DisplayName("새 인덱스 생성 → 채우기 → refresh → alias 이동 → 따라잡기 색인(alias) → 옛 인덱스 삭제 순서")
    void rebuildOrder() {
        given(searchIndex.bulkInto(eq("products_v2"), anyList())).willReturn(new BulkResult(1, 0, 0));
        given(searchIndex.bulk(anyList())).willReturn(new BulkResult(0, 1, 0));

        RebuildResult result = service.rebuild();

        InOrder order = inOrder(searchIndex);
        order.verify(searchIndex).createNextIndex("products_v1", null);
        order.verify(searchIndex).bulkInto(eq("products_v2"), anyList());
        order.verify(searchIndex).refresh("products_v2");
        order.verify(searchIndex).switchAlias("products_v1", "products_v2");
        order.verify(searchIndex).bulk(anyList());
        order.verify(searchIndex).deleteIndex("products_v1");
        assertThat(result.previousIndex()).isEqualTo("products_v1");
        assertThat(result.currentIndex()).isEqualTo("products_v2");
        assertThat(result.filled().indexed()).isEqualTo(1);
        assertThat(result.caughtUp().skipped()).isEqualTo(1);
        assertThat(result.previousDeleted()).isTrue();
    }

    @Test
    @DisplayName("새 인덱스를 채우다 실패한 건이 있으면 새 인덱스를 지우고 alias는 옮기지 않음")
    void failedFillDiscardsNewIndex() {
        given(searchIndex.bulkInto(eq("products_v2"), anyList())).willReturn(new BulkResult(0, 0, 1));

        assertThatThrownBy(() -> service.rebuild()).isInstanceOf(IllegalStateException.class);

        verify(searchIndex).deleteIndex("products_v2");
        verify(searchIndex, never()).switchAlias(anyString(), anyString());
        verify(searchIndex, never()).deleteIndex("products_v1");
    }

    @Test
    @DisplayName("채우는 중 연결이 끊겨도 새 인덱스를 지우고 alias는 그대로")
    void interruptedFillDiscardsNewIndex() {
        given(searchIndex.bulkInto(eq("products_v2"), anyList())).willThrow(new IllegalStateException("연결 끊김"));

        assertThatThrownBy(() -> service.rebuild()).isInstanceOf(IllegalStateException.class);

        verify(searchIndex).deleteIndex("products_v2");
        verify(searchIndex, never()).switchAlias(anyString(), anyString());
    }

    @Test
    @DisplayName("[#563] 단계(채우기 → alias 이동 → 따라잡기 → 정리)와 배치 결과를 작업 기록에 남김")
    void recordsProgress() {
        given(searchIndex.bulkInto(eq("products_v2"), anyList())).willReturn(new BulkResult(1, 0, 0));
        given(searchIndex.bulk(anyList())).willReturn(new BulkResult(0, 1, 0));
        IndexProgress progress = mock(IndexProgress.class);

        service.rebuild(progress);

        InOrder order = inOrder(progress, searchIndex);
        order.verify(progress).phase(SearchIndexJobPhase.FILLING, "products_v1", "products_v2");
        order.verify(searchIndex).bulkInto(eq("products_v2"), anyList());
        order.verify(progress).batch(1, 0, 0);
        order.verify(searchIndex).refresh("products_v2");
        order.verify(progress).phase(SearchIndexJobPhase.SWITCHING_ALIAS, "products_v1", "products_v2");
        order.verify(searchIndex).switchAlias("products_v1", "products_v2");
        order.verify(progress).phase(SearchIndexJobPhase.CATCHING_UP, "products_v1", "products_v2");
        order.verify(progress).batch(1, 1, 0);
        order.verify(progress).phase(SearchIndexJobPhase.CLEANING_UP, "products_v1", "products_v2");
        order.verify(searchIndex).deleteIndex("products_v1");
    }

    @Test
    @DisplayName("[#563] 죽은 작업으로 실패 처리된 실행은 새 인덱스를 지우지 않고 멈춤 (같은 이름을 다음 재색인이 쓰고 있을 수 있음)")
    void abandonedRunDoesNotDiscardNewIndex() {
        given(searchIndex.bulkInto(eq("products_v2"), anyList())).willReturn(new BulkResult(1, 0, 0));
        IndexProgress progress = mock(IndexProgress.class);
        doThrow(new SearchIndexJobAbandonedException(1L)).when(progress).batch(1, 0, 0);

        assertThatThrownBy(() -> service.rebuild(progress)).isInstanceOf(SearchIndexJobAbandonedException.class);

        verify(searchIndex, never()).deleteIndex(anyString());
        verify(searchIndex, never()).switchAlias(anyString(), anyString());
    }

    @Test
    @DisplayName("[#563] alias를 옮기기 직전 확인에서 작업이 이미 실패 처리됐으면 alias를 옮기지 않고 인덱스도 지우지 않음")
    void abandonedBeforeSwitchDoesNotMoveAlias() {
        given(searchIndex.bulkInto(eq("products_v2"), anyList())).willReturn(new BulkResult(1, 0, 0));
        IndexProgress progress = mock(IndexProgress.class);
        doThrow(new SearchIndexJobAbandonedException(1L)).when(progress)
                .phase(SearchIndexJobPhase.SWITCHING_ALIAS, "products_v1", "products_v2");

        assertThatThrownBy(() -> service.rebuild(progress)).isInstanceOf(SearchIndexJobAbandonedException.class);

        verify(searchIndex).refresh("products_v2");
        verify(searchIndex, never()).switchAlias(anyString(), anyString());
        verify(searchIndex, never()).deleteIndex(anyString());
    }

    @Test
    @DisplayName("[#563] 작업으로 실행하면 새 인덱스 이름에 쓸 작업 ID를 넘김")
    void passesJobIdForNewIndexName() {
        given(searchIndex.createNextIndex("products_v1", 12L)).willReturn("products_v2_j12");
        given(searchIndex.bulkInto(eq("products_v2_j12"), anyList())).willReturn(new BulkResult(1, 0, 0));
        given(searchIndex.bulk(anyList())).willReturn(new BulkResult(0, 1, 0));
        IndexProgress progress = mock(IndexProgress.class);
        given(progress.jobId()).willReturn(12L);

        RebuildResult result = service.rebuild(progress);

        assertThat(result.currentIndex()).isEqualTo("products_v2_j12");
        verify(searchIndex).switchAlias("products_v1", "products_v2_j12");
    }

    @Test
    @DisplayName("옛 인덱스를 못 지워도 재색인은 성공 (alias는 이미 옮김), previousDeleted=false")
    void previousDeleteFailureStillSucceeds() {
        given(searchIndex.bulkInto(eq("products_v2"), anyList())).willReturn(new BulkResult(1, 0, 0));
        given(searchIndex.bulk(anyList())).willReturn(new BulkResult(0, 1, 0));
        org.mockito.Mockito.doThrow(new IllegalStateException("삭제 실패")).when(searchIndex).deleteIndex("products_v1");

        RebuildResult result = service.rebuild();

        assertThat(result.currentIndex()).isEqualTo("products_v2");
        assertThat(result.previousDeleted()).isFalse();
    }

    @Test
    @DisplayName("alias를 옮긴 뒤 따라잡기 색인에 실패한 건이 있으면 옛 인덱스를 지우지 않음 (그 상품의 최신 값이 옛 인덱스에만 있을 수 있음)")
    void failedCatchUpKeepsPreviousIndex() {
        given(searchIndex.bulkInto(eq("products_v2"), anyList())).willReturn(new BulkResult(1, 0, 0));
        given(searchIndex.bulk(anyList())).willReturn(new BulkResult(0, 0, 1));

        RebuildResult result = service.rebuild();

        verify(searchIndex).switchAlias("products_v1", "products_v2");
        verify(searchIndex, never()).deleteIndex("products_v1");
        assertThat(result.caughtUp().failed()).isEqualTo(1);
        assertThat(result.previousDeleted()).isFalse();
    }

    private static Product product(Long id) {
        Species species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "id", 62L);
        ReflectionTestUtils.setField(species, "name", "몬스테라");
        Product product = Product.create(species, 7L, "상품 " + id, "설명", new BigDecimal("10000"), null, null, null,
                List.of());
        ReflectionTestUtils.setField(product, "id", id);
        ReflectionTestUtils.setField(product, "status", ProductStatus.ACTIVE);
        ReflectionTestUtils.setField(product, "version", 1L);
        return product;
    }
}
