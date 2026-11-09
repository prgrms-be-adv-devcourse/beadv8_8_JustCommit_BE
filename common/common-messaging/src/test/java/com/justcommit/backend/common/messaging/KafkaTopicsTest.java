package com.justcommit.backend.common.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTopicsTest {

  @Test
  @DisplayName("토픽 상수는 docs/reference/kafka-topics.md의 12개와 같다")
  void matchesTopicDocument() {
    assertThat(topicConstants()).containsExactlyInAnyOrder(
            "member.events.v1",
            "member.address.events.v1",
            "market.events.v1",
            "market.order-paid.v1",
            "market.order-expired.v1",
            "market.order-failed.v1",
            "market.order-item-cancelled.v1",
            "market.order-item-returned.v1",
            "product.events.v1",
            "product.review.v1",
            "species.events.v1",
            "settlement.events.v1");
  }

  // KafkaTopics의 public static final String 값을 모두 꺼냄 (상수를 추가하면 위 목록도 같이 고쳐야 통과)
  private static List<String> topicConstants() {
    return Arrays.stream(KafkaTopics.class.getDeclaredFields())
            .filter(field -> Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers()))
            .map(KafkaTopicsTest::valueOf)
            .toList();
  }

  private static String valueOf(Field field) {
    try {
      return (String) field.get(null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }
}
