package com.justcommit.backend.product.messaging;

import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.product.ProductUseCase;
import com.justcommit.backend.common.messaging.consumer.MessagingConsumerAutoConfiguration;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
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
    @Autowired KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired KafkaProperties kafkaProperties;
    @MockitoBean ProductUseCase productUseCase;

    // 리스너 4개 × concurrency 3 = 컨슈머 12개가 모두 그룹에 들어와 파티션을 나눠 받을 때까지 기다린 뒤 메시지를 보냄 (#468)
    // 일부만 들어온 상태에서 보내면 재시도 도중 늦게 온 컨슈머 때문에 리밸런스가 일어나고, 파티션을 새로 맡은 컨슈머가
    // 재시도 횟수(컨슈머 메모리)를 처음부터 다시 세어 호출이 4번보다 많아짐
    @BeforeEach
    void waitUntilConsumerGroupIsStable() throws Exception {
        int expectedMembers = 0;
        Set<String> topics = new HashSet<>();
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            expectedMembers += container instanceof ConcurrentMessageListenerContainer<?, ?> concurrent
                    ? concurrent.getConcurrency() : 1;
            topics.addAll(List.of(container.getContainerProperties().getTopics()));
        }
        String groupId = kafkaProperties.getConsumer().getGroupId();

        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                if (isStable(admin, groupId, topics, expectedMembers)) {
                    return;
                }
                Thread.sleep(200);
            }
        }
        throw new AssertionError("60초 안에 컨슈머 " + expectedMembers + "개가 그룹 " + groupId + "에 모두 들어오지 않음");
    }

    // 그룹이 안정(STABLE)이고, 컨슈머가 모두 들어왔고, 구독한 토픽의 파티션이 모두 배정됨
    // 토픽은 컨슈머가 구독하면서 자동 생성되므로 처음 몇 번은 아직 없을 수 있음 → false로 보고 다시 확인
    private static boolean isStable(Admin admin, String groupId, Set<String> topics, int expectedMembers) throws InterruptedException {
        try {
            int expectedPartitions = admin.describeTopics(topics).allTopicNames().get().values().stream()
                    .mapToInt(topic -> topic.partitions().size()).sum();
            ConsumerGroupDescription group = admin.describeConsumerGroups(List.of(groupId)).all().get().get(groupId);
            int assignedPartitions = group.members().stream()
                    .mapToInt(member -> member.assignment().topicPartitions().size()).sum();
            return group.groupState() == GroupState.STABLE
                    && group.members().size() == expectedMembers
                    && assignedPartitions == expectedPartitions;
        } catch (ExecutionException e) {
            return false;
        }
    }

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
