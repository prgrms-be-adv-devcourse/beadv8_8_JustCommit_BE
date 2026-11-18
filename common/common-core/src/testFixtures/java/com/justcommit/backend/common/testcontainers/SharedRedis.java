package com.justcommit.backend.common.testcontainers;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

/**
 * 테스트 JVM 하나에서 한 번만 띄워 함께 쓰는 Redis. 쓰는 법은 {@link SharedPostgres}와 같다.
 *
 * <p>키가 클래스 사이에 남으므로 테스트마다 자기 키를 지우거나 {@code FLUSHDB}로 정리한다.
 */
public final class SharedRedis {

    private static final int PORT = 6379;
    private static final GenericContainer<?> CONTAINER = new GenericContainer<>("redis:8-alpine").withExposedPorts(PORT);

    static {
        CONTAINER.start();
    }

    private SharedRedis() {
    }

    public static GenericContainer<?> container() {
        return CONTAINER;
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", CONTAINER::getHost);
        registry.add("spring.data.redis.port", () -> CONTAINER.getMappedPort(PORT));
    }
}
