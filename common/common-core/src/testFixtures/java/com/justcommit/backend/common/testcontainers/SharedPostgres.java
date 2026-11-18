package com.justcommit.backend.common.testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 JVM 하나(= Gradle 모듈 하나)에서 한 번만 띄워 모든 테스트 클래스가 함께 쓰는 PostgreSQL.
 *
 * <p>쓰는 법: 테스트 클래스의 {@code @DynamicPropertySource}에서 {@link #register(DynamicPropertyRegistry)}를 부른다.
 * 처음 부를 때 컨테이너가 뜨고, JVM이 끝나면 Testcontainers(Ryuk)가 지운다.
 *
 * <p>컨테이너는 하나지만 부를 때마다(= 스프링 컨텍스트마다) 빈 데이터베이스를 새로 만들어 연결한다.
 * 그래서 클래스끼리 데이터 · 스키마(Flyway, ddl-auto)가 섞이지 않는다.
 *
 * <p>이미지는 기본 {@code postgres:17-alpine}이고, pgvector가 필요한 모듈은 build.gradle의 test 태스크에서
 * {@code systemProperty 'shared.postgres.image', 'pgvector/pgvector:pg17'}로 바꾼다.
 */
public final class SharedPostgres {

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("shared.postgres.image", "postgres:17-alpine")).asCompatibleSubstituteFor("postgres"))
            // 캐시된 스프링 컨텍스트마다 커넥션 풀(기본 10개)을 잡으므로 기본값 100보다 넉넉하게
            .withCommand("postgres", "-c", "max_connections=300");
    private static final AtomicInteger DATABASE_SEQ = new AtomicInteger();

    static {
        CONTAINER.start();
    }

    private SharedPostgres() {
    }

    public static PostgreSQLContainer container() {
        return CONTAINER;
    }

    public static void register(DynamicPropertyRegistry registry) {
        String url = createDatabase();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", CONTAINER::getUsername);
        registry.add("spring.datasource.password", CONTAINER::getPassword);
    }

    /** 빈 데이터베이스를 새로 만들고 JDBC URL을 돌려준다. 스프링 없이 JDBC · Flyway로 직접 붙는 테스트용 */
    public static String createDatabase() {
        String name = "test_" + DATABASE_SEQ.incrementAndGet();
        try (Connection connection = DriverManager.getConnection(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("create database " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("테스트 데이터베이스를 만들지 못했습니다: " + name, e);
        }
        return "jdbc:postgresql://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name;
    }
}
