package com.justcommit.backend.product.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.product.messaging.event.ProductChanged;
import com.justcommit.backend.product.search.application.ProductIndexService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// product.events.v1 (자기 토픽) → OpenSearch 상품 문서 갱신 (#485, ADR 0005)
// 상품 저장은 Outbox까지만 하고 끝나므로, OpenSearch가 죽어도 등록 · 수정 API는 성공한다. 색인만 늦어짐
// 실패(OpenSearch 연결 · alias 없음 등)는 예외를 그대로 던져 공통 소비 설정대로 1 · 2 · 4초 재시도 후 product.events.v1.DLT
// Key가 productId라 한 상품의 이벤트는 한 파티션에서 순서대로 온다
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductSearchIndexListener {

    // 상품 값이 바뀌는 이벤트만. ProductApproved · ProductRejected는 알림용이고 상태는 함께 나가는 ProductStatusChanged가 알림
    static final Set<String> INDEXED_EVENT_TYPES = Set.of("ProductRegistered", "ProductUpdated", "ProductStatusChanged");

    private final EventEnvelopeMapper mapper;
    private final ProductIndexService productIndexService;

    @KafkaListener(topics = "${PRODUCT_EVENTS_TOPIC:" + KafkaTopics.PRODUCT_EVENTS + "}")
    public void onProductEvent(EventEnvelope envelope) {
        if (!INDEXED_EVENT_TYPES.contains(envelope.eventType())) {
            return;
        }
        // payload에서 productId만 쓴다. 나머지 값은 DB에서 최신으로 다시 읽음 (늦게 온 이벤트의 옛 값을 쓰지 않게)
        ProductChanged event = mapper.readPayload(envelope, ProductChanged.class);
        log.debug("상품 색인 이벤트: eventId={}, eventType={}, productId={}",
                envelope.eventId(), envelope.eventType(), event.productId());
        productIndexService.sync(event.productId());
    }
}
