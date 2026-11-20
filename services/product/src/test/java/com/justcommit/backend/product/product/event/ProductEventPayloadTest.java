package com.justcommit.backend.product.product.event;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.product.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// product.events.v1 payload v3 모양 (docs/reference/kafka-topics.md, ADR 0021 · 0030). 칸을 바꾸면 version을 올리고 문서도 고친다
@DisplayName("product.events.v1 이벤트 payload")
class ProductEventPayloadTest {

    private static final ProductEventSource SOURCE =
            new ProductEventSource(42L, 7L, 3L, ProductStatus.SOLD_OUT, true, 5L, "몬스테라 중품",
                    new BigDecimal("25000"), "https://img/1.jpg", "잎이 큰 몬스테라", "겉흙이 마르면 물", 60, "중형");

    private final EventEnvelopeMapper mapper = new EventEnvelopeMapper();

    static Stream<Arguments> events() {
        return Stream.of(
                Arguments.of(ProductRegistered.from(SOURCE), "ProductRegistered"),
                Arguments.of(ProductUpdated.from(SOURCE), "ProductUpdated"),
                Arguments.of(ProductStatusChanged.from(SOURCE), "ProductStatusChanged"));
    }

    @ParameterizedTest(name = "{1}: product.events.v1 · Key productId · 봉투 version 3, payload 열세 칸")
    @MethodSource("events")
    void payload(IntegrationEvent event, String eventType) {
        EventEnvelope envelope = mapper.wrap(event);
        JsonNode payload = envelope.payload();

        assertThat(event.topic()).isEqualTo(KafkaTopics.PRODUCT_EVENTS);
        assertThat(envelope.eventType()).isEqualTo(eventType);
        assertThat(envelope.version()).isEqualTo(3);
        assertThat(envelope.aggregateId()).isEqualTo("42");
        assertThat(fieldNames(payload)).containsExactlyInAnyOrder(
                "productId", "speciesId", "sellerId", "status", "hidden", "productVersion",
                "title", "price", "thumbnailUrl", "description", "growNote", "heightCm", "potSize");
        assertThat(payload.get("productId").asLong()).isEqualTo(42L);
        assertThat(payload.get("speciesId").asLong()).isEqualTo(7L);
        assertThat(payload.get("sellerId").asLong()).isEqualTo(3L);
        assertThat(payload.get("status").asString()).isEqualTo("SOLD_OUT");
        assertThat(payload.get("hidden").isBoolean()).isTrue();
        assertThat(payload.get("hidden").asBoolean()).isTrue();
        assertThat(payload.get("productVersion").isNumber()).isTrue();
        assertThat(payload.get("productVersion").asLong()).isEqualTo(5L);
        assertThat(payload.get("title").asString()).isEqualTo("몬스테라 중품");
        assertThat(payload.get("price").decimalValue()).isEqualByComparingTo("25000");
        assertThat(payload.get("thumbnailUrl").asString()).isEqualTo("https://img/1.jpg");
        assertThat(payload.get("description").asString()).isEqualTo("잎이 큰 몬스테라");
        assertThat(payload.get("growNote").asString()).isEqualTo("겉흙이 마르면 물");
        assertThat(payload.get("heightCm").asInt()).isEqualTo(60);
        assertThat(payload.get("potSize").asString()).isEqualTo("중형");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
