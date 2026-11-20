package com.justcommit.backend.product.messaging;

import static com.justcommit.backend.product.messaging.EventMessages.event;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.product.search.application.ProductIndexService;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductSearchIndexListenerTest {

    private ProductIndexService productIndexService;
    private ProductSearchIndexListener listener;

    @BeforeEach
    void setUp() {
        productIndexService = mock(ProductIndexService.class);
        listener = new ProductSearchIndexListener(new EventEnvelopeMapper(), productIndexService);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ProductRegistered", "ProductUpdated", "ProductStatusChanged"})
    @DisplayName("상품 값이 바뀌는 이벤트면 payload의 상품 ID로 문서를 맞춘다 (다른 값은 DB에서 다시 읽음)")
    void indexedEvents(String eventType) {
        listener.onProductEvent(event(eventType, 10,
                Map.of("productId", 10, "speciesId", 62, "sellerId", 7, "status", "ACTIVE", "hidden", false,
                        "productVersion", 3)));

        verify(productIndexService).sync(10L);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ProductApproved", "ProductRejected"})
    @DisplayName("알림용 이벤트는 건너뛴다 (상태는 함께 나가는 ProductStatusChanged로 반영)")
    void notificationEventsAreSkipped(String eventType) {
        listener.onProductEvent(event(eventType, 10, Map.of("productId", 10, "sellerId", 7)));

        verifyNoInteractions(productIndexService);
    }

    @Test
    @DisplayName("OpenSearch 오류는 삼키지 않고 던진다 (공통 소비 설정이 1 · 2 · 4초 재시도 후 DLT)")
    void failureIsRethrown() {
        willThrow(new UncheckedIOException(new ConnectException("refused"))).given(productIndexService).sync(10L);

        assertThatThrownBy(() -> listener.onProductEvent(event("ProductUpdated", 10, Map.of("productId", 10))))
                .isInstanceOf(UncheckedIOException.class);
    }
}
