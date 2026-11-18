package com.justcommit.backend.common.testcontainers;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 테스트 JVM 하나에서 한 번만 띄워 함께 쓰는 Kafka. 쓰는 법은 {@link SharedPostgres}와 같다.
 * 쓰는 서비스는 {@code testImplementation 'org.testcontainers:testcontainers-kafka'}가 있어야 한다.
 *
 * <p>토픽 · 메시지가 클래스 사이에 남으므로 클래스마다 컨슈머 그룹 id를 다르게 하고,
 * 메시지 key(주문 번호 등)도 겹치지 않게 만든다.
 */
public final class SharedKafka {

    private static final KafkaContainer CONTAINER = new KafkaContainer("apache/kafka:4.2.1");

    static {
        CONTAINER.start();
    }

    private SharedKafka() {
    }

    public static KafkaContainer container() {
        return CONTAINER;
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", CONTAINER::getBootstrapServers);
    }
}
