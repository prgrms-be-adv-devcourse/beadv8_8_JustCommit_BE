package com.justcommit.backend.product.product.event;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// product.events.v1 payload 모양 (docs/reference/kafka-topics.md). 칸을 바꾸면 version을 올리고 문서도 고친다
class ProductEventPayloadTest {

  private final EventEnvelopeMapper mapper = new EventEnvelopeMapper();

  static Stream<Arguments> events() {
    return Stream.of(
        Arguments.of(new ProductRegistered(42L), "ProductRegistered"),
        Arguments.of(new ProductUpdated(42L), "ProductUpdated"),
        Arguments.of(new ProductStatusChanged(42L), "ProductStatusChanged"));
  }

  @ParameterizedTest(name = "{1}: product.events.v1 · Key productId, payload는 productId 한 칸")
  @MethodSource("events")
  void payload(IntegrationEvent event, String eventType) {
    EventEnvelope envelope = mapper.wrap(event);
    JsonNode payload = envelope.payload();

    assertThat(event.topic()).isEqualTo(KafkaTopics.PRODUCT_EVENTS);
    assertThat(envelope.eventType()).isEqualTo(eventType);
    assertThat(envelope.version()).isEqualTo(1);
    assertThat(envelope.aggregateId()).isEqualTo("42");
    assertThat(fieldNames(payload)).containsExactly("productId");
    assertThat(payload.get("productId").isNumber()).isTrue();
    assertThat(payload.get("productId").asLong()).isEqualTo(42L);
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.propertyNames().forEach(names::add);
    return names;
  }
}