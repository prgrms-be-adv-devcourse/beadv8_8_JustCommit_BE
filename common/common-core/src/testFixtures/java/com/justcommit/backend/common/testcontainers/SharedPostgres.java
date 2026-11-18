package com.justcommit.backend.common.testcontainers;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 테스트 JVM 하나(= Gradle 모듈 하나)에서 한 번만 띄워 모든 테스트 클래스가 함께 쓰는 PostgreSQL.
 *
 * <p>쓰는 법: 테스트 클래스의 {@code @DynamicPropertySource}에서 {@link #register(DynamicPropertyRegistry)}를 부른다.
 * 처음 부를 때 컨테이너가 뜨고, JVM이 끝나면 Testcontainers(Ryuk)가 지운다.
 *
 * <p>여러 클래스가 같은 DB를 쓰므로 커밋하는 테스트는 {@code @AfterEach}에서 자기가 넣은 행을 지운다.
 */
public final class SharedPostgres {

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:17-alpine")
            // 캐시된 스프링 컨텍스트마다 커넥션 풀(기본 10개)을 잡으므로 기본값 100보다 넉넉하게
            .withCommand("postgres", "-c", "max_connections=300");

    static {
        CONTAINER.start();
    }

    private SharedPostgres() {
    }

    public static PostgreSQLContainer container() {
        return CONTAINER;
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", CONTAINER::getUsername);
        registry.add("spring.datasource.password", CONTAINER::getPassword);
    }
}
