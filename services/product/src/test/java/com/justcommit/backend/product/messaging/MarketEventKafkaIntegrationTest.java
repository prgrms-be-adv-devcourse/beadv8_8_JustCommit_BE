package com.justcommit.backend.product.messaging;

import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.product.ProductUseCase;
import com.justcommit.backend.common.messaging.consumer.MessagingConsumerAutoConfiguration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static com.justcommit.backend.product.messaging.EventMessages.envelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 실제 Kafka로 구독 · 제자리 재시도 · DLT를 확인 (DB 없이 ProductUseCase는 목)
@SpringBootTest(classes = MarketEventListener.class)
@ImportAutoConfiguration({KafkaAutoConfiguration.class, MessagingConsumerAutoConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class MarketEventKafkaIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    // 그룹 · 직렬화 설정은 application.yml을 그대로 쓰고, 주소만 테스트 컨테이너로 바꿈
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @MockitoBean ProductUseCase productUseCase;

    @Test
    @DisplayName("판매 완료 이벤트를 받으면 상품을 판매 완료로 바꾼다")
    void consumesOrderPaid() {
        kafkaTemplate.send(KafkaTopics.MARKET_ORDER_PAID, "100", envelope("OrderPaid", 100, Map.of("orderId", 100)));

        verify(productUseCase, timeout(20_000)).markSoldOut(100L);
    }

    @Test
    @DisplayName("형식이 깨진 메시지는 재시도 없이 원래토픽.DLT로 간다")
    void malformedMessageGoesToDltWithoutRetry() {
        kafkaTemplate.send(KafkaTopics.MARKET_ORDER_EXPIRED, "200", "not-json");

        List<ConsumerRecord<String, String>> dlt = pollDlt(KafkaTopics.MARKET_ORDER_EXPIRED + ".DLT", "200");
        assertThat(dlt).singleElement().satisfies(r -> assertThat(r.value()).isEqualTo("not-json"));
    }

    @Test
    @DisplayName("처리에 계속 실패하면 1 · 2 · 4초 제자리 재시도 뒤 DLT로 간다")
    void failingMessageRetriesThenGoesToDlt() {
        when(productUseCase.release(anyLong())).thenThrow(new IllegalStateException("DB 장애"));

        kafkaTemplate.send(KafkaTopics.MARKET_ORDER_FAILED, "300", envelope("OrderFailed", 300, Map.of("orderId", 300)));

        List<ConsumerRecord<String, String>> dlt = pollDlt(KafkaTopics.MARKET_ORDER_FAILED + ".DLT", "300");
        assertThat(dlt).hasSize(1);
        // 처음 1번 + 재시도 3번
        verify(productUseCase, times(4)).release(300L);
    }

    // DLT에서 이 key의 메시지가 올 때까지 읽는다 (재시도 1 + 2 + 4초를 넘게 기다림)
    private List<ConsumerRecord<String, String>> pollDlt(String dltTopic, String key) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(dltTopic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                List<ConsumerRecord<String, String>> found = StreamSupport
                        .stream(consumer.poll(Duration.ofMillis(500)).spliterator(), false)
                        .filter(r -> key.equals(r.key()))
                        .toList();
                if (!found.isEmpty()) {
                    return found;
                }
            }
            return List.of();
        }
    }
}
