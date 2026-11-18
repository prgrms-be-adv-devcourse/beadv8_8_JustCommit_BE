package com.justcommit.backend.common.testcontainers;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 테스트 JVM 하나에서 한 번만 띄워 함께 쓰는 Kafka. 쓰는 법은 {@link SharedPostgres}와 같다.
 * 쓰는 서비스는 {@code testImplementation 'org.testcontainers:testcontainers-kafka'}가 있어야 한다.
 *
 * <p>토픽 · 메시지가 클래스 사이에 남는다. 그래서
 * <ul>
 *   <li>{@link #register(DynamicPropertyRegistry)}는 부를 때마다(= 스프링 컨텍스트마다) 컨슈머 그룹 id를 새로 정한다</li>
 *   <li>리스너가 도는 테스트 클래스는 {@code @DirtiesContext}를 붙여, 끝난 뒤 다른 클래스의 메시지를 읽지 않게 한다</li>
 *   <li>보낸 메시지를 찾을 때는 key(주문 번호 등)로 거르고, 클래스끼리 key가 겹치지 않게 만든다</li>
 * </ul>
 */
public final class SharedKafka {

    private static final KafkaContainer CONTAINER = new KafkaContainer("apache/kafka:4.2.1");
    private static final AtomicInteger GROUP_SEQ = new AtomicInteger();

    static {
        CONTAINER.start();
    }

    private SharedKafka() {
    }

    public static KafkaContainer container() {
        return CONTAINER;
    }

    public static void register(DynamicPropertyRegistry registry) {
        String groupId = "test-group-" + GROUP_SEQ.incrementAndGet();
        registry.add("spring.kafka.bootstrap-servers", CONTAINER::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> groupId);
    }
}
