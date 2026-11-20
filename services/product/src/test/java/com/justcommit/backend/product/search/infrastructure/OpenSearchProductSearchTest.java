package com.justcommit.backend.product.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.search.application.ProductDocument;
import com.justcommit.backend.product.search.application.ProductKeywordSearch;
import com.justcommit.backend.product.search.application.ProductSearchSort;
import com.justcommit.backend.product.search.application.ProductSearchUnavailableException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;

// 실패 종류마다 다시 보내는지 · LIKE 대체로 넘기는지 (#574). 실제 OpenSearch 동작은 ProductSearchIntegrationTest
@ExtendWith(MockitoExtension.class)
class OpenSearchProductSearchTest {

    private static final ProductKeywordSearch.Condition CONDITION = new ProductKeywordSearch.Condition(
            "몬스테라", List.of(ProductStatus.ACTIVE), null, null, null, null, null, null, null);

    @Mock OpenSearchClient client;

    @Test
    @DisplayName("연결 거부는 요청이 닿지 않았으므로 1번 더 보낸 뒤 대체로 넘긴다")
    void retriesOnceOnConnectionRefused() throws Exception {
        given(client.search(any(SearchRequest.class), eq(ProductDocument.class))).willThrow(new ConnectException("refused"));

        assertThatThrownBy(this::search).isInstanceOf(ProductSearchUnavailableException.class);
        verify(client, times(2)).search(any(SearchRequest.class), eq(ProductDocument.class));
    }

    @Test
    @DisplayName("연결 시간 초과도 연결 실패라 1번 더 보낸다 (ConnectTimeoutException은 SocketTimeoutException의 하위)")
    void retriesOnceOnConnectTimeout() throws Exception {
        given(client.search(any(SearchRequest.class), eq(ProductDocument.class)))
                .willThrow(new ConnectTimeoutException("connect timeout"));

        assertThatThrownBy(this::search).isInstanceOf(ProductSearchUnavailableException.class);
        verify(client, times(2)).search(any(SearchRequest.class), eq(ProductDocument.class));
    }

    @Test
    @DisplayName("읽기 시간 초과는 다시 보내지 않고 바로 대체로 넘긴다 (응답 없이 멈춘 OpenSearch를 2번 기다리지 않게)")
    void doesNotRetryOnReadTimeout() throws Exception {
        given(client.search(any(SearchRequest.class), eq(ProductDocument.class)))
                .willThrow(new SocketTimeoutException("2 SECONDS"));

        assertThatThrownBy(this::search).isInstanceOf(ProductSearchUnavailableException.class);
        verify(client, times(1)).search(any(SearchRequest.class), eq(ProductDocument.class));
    }

    @Test
    @DisplayName("연결 풀 빌리기 대기 초과(RuntimeException으로 감싼 TimeoutException)는 바로 대체로 넘긴다")
    void fallsBackWhenPoolIsExhausted() throws Exception {
        given(client.search(any(SearchRequest.class), eq(ProductDocument.class)))
                .willThrow(new RuntimeException(new TimeoutException("Deadline exceeded")));

        assertThatThrownBy(this::search).isInstanceOf(ProductSearchUnavailableException.class);
        verify(client, times(1)).search(any(SearchRequest.class), eq(ProductDocument.class));
    }

    @Test
    @DisplayName("시간 초과와 관계없는 RuntimeException은 장애로 보지 않고 그대로 던진다 (코드 버그를 LIKE로 숨기지 않게)")
    void rethrowsOtherRuntimeExceptions() throws Exception {
        given(client.search(any(SearchRequest.class), eq(ProductDocument.class)))
                .willThrow(new IllegalStateException("bug"));

        assertThatThrownBy(this::search).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("동시 검색 상한이 차 있으면 OpenSearch를 부르지 않고 대기 시간(0.2초)만 기다린 뒤 대체로 넘긴다")
    void fallsBackWhenConcurrencyLimitIsFull() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(client.search(any(SearchRequest.class), eq(ProductDocument.class))).willAnswer(invocation -> {
            entered.countDown();
            release.await(); // 응답 없는 OpenSearch를 기다리는 첫 요청
            throw new SocketTimeoutException("2 SECONDS");
        });
        OpenSearchProductSearch limited = new OpenSearchProductSearch(client, 1, Duration.ofMillis(200));
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            try {
                limited.search(CONDITION, ProductSearchSort.RELEVANCE, 0, 20);
            } catch (ProductSearchUnavailableException expected) {
                // 첫 요청은 읽기 시간 초과로 대체
            }
        });
        entered.await();

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> limited.search(CONDITION, ProductSearchSort.RELEVANCE, 0, 20))
                .isInstanceOf(ProductSearchUnavailableException.class)
                .hasMessageContaining("동시 검색 상한");
        assertThat(System.currentTimeMillis() - started).isBetween(150L, 1_000L);
        verify(client, times(1)).search(any(SearchRequest.class), eq(ProductDocument.class));

        release.countDown();
        first.get();
    }

    private void search() {
        new OpenSearchProductSearch(client).search(CONDITION, ProductSearchSort.RELEVANCE, 0, 20);
    }
}
