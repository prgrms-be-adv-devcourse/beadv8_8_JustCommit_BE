package com.justcommit.backend.product.product.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;
import java.math.BigDecimal;

// 상품 수정 (제목 · 가격 · 사진 · 품종 등)
// payload v3: 바뀐 뒤 품종 · 판매자 · 상태 · 숨김 · 상품 버전 (ADR 0021) + 제목 · 가격 · 대표 사진 · 설명 · 키우기 메모 · 크기 (ADR 0031)
// 형식은 docs/reference/kafka-topics.md
// 받는 쪽(ai 상품 스냅샷)은 productVersion이 저장된 값보다 클 때만 반영한다. 발행은 ProductEventPublisher로만
public record ProductUpdated(Long productId, Long speciesId, Long sellerId, String status, boolean hidden,
                     long productVersion, String title, BigDecimal price, String thumbnailUrl,
                     String description, String growNote, Integer heightCm, String potSize)
        implements IntegrationEvent {

    public static ProductUpdated from(ProductEventSource source) {
        return new ProductUpdated(source.productId(), source.speciesId(), source.sellerId(),
                source.status().name(), source.hidden(), source.productVersion(), source.title(), source.price(),
                source.thumbnailUrl(), source.description(), source.growNote(), source.heightCm(), source.potSize());
    }

    @Override
    public String topic() {
        return KafkaTopics.PRODUCT_EVENTS;
    }

    @Override
    public String eventType() {
        return "ProductUpdated";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(productId);
    }

    // v1(productId만) → v2(상태 값을 실음) → v3(제목 · 설명 등 상품 문서 · 카드 값을 실음)
    @Override
    public int version() {
        return 3;
    }
}
