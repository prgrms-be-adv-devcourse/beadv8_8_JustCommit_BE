package com.justcommit.backend.common.testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.util.ClassUtils;

/**
 * 테스트 클래스가 시작하기 전에 DB를 「마이그레이션 직후」 상태로 되돌리고 Redis를 비운다 (#664).
 *
 * <p>모듈 공통 베이스 클래스로 스프링 컨텍스트(= DB 하나)를 여러 테스트 클래스가 같이 쓸 때, 예전 「컨텍스트마다 빈 DB」와
 * 같은 격리를 만든다. 베이스 클래스에 {@code @TestExecutionListeners(listeners = ResetDataBeforeClassListener.class,
 * mergeMode = MERGE_WITH_DEFAULTS)}로 붙인다.
 *
 * <ol>
 *   <li>컨텍스트를 처음 쓸 때(마이그레이션 직후) 행이 있는 테이블을 기본 데이터로 보고 {@code test_seed} 스키마에 복사해 둔다
 *       (예: product 품종 목록, payment 시스템 지갑)</li>
 *   <li>클래스마다 {@code flyway_schema_history}를 뺀 모든 테이블을 비우고, 기본 데이터를 다시 넣는다.
 *       기본 데이터 테이블의 id 시퀀스는 다시 넣은 최댓값 뒤로 맞춘다</li>
 *   <li>Redis가 있으면 {@code flushAll}</li>
 * </ol>
 */
public class ResetDataBeforeClassListener extends AbstractTestExecutionListener {

    private static final String SEED_SCHEMA = "test_seed";
    private static final String REDIS_FACTORY = "org.springframework.data.redis.connection.RedisConnectionFactory";

    @Override
    public int getOrder() {
        // 의존성 주입(2000)보다 먼저
        return 1500;
    }

    @Override
    public void beforeTestClass(TestContext testContext) throws Exception {
        ApplicationContext context = testContext.getApplicationContext();
        context.getBeanProvider(DataSource.class).ifAvailable(ResetDataBeforeClassListener::resetDatabase);
        if (ClassUtils.isPresent(REDIS_FACTORY, getClass().getClassLoader())) {
            RedisReset.flushAll(context);
        }
    }

    private static void resetDatabase(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            List<String> tables = queryList(statement,
                "select tablename from pg_tables where schemaname = current_schema() and tablename <> 'flyway_schema_history'");
            if (!seedSaved(statement)) {
                saveSeed(statement, tables);
                return; // 처음이면 이미 마이그레이션 직후 상태
            }
            if (!tables.isEmpty()) {
                statement.execute("truncate table " + quoteAll(tables) + " restart identity cascade");
            }
            for (String seed : queryList(statement,
                "select tablename from pg_tables where schemaname = '" + SEED_SCHEMA + "'")) {
                statement.execute("insert into " + quote(seed) + " overriding system value select * from "
                    + SEED_SCHEMA + "." + quote(seed));
                for (String column : queryList(statement,
                    "select column_name from information_schema.columns where table_schema = current_schema() and table_name = '"
                        + seed + "' and (is_identity = 'YES' or column_default like 'nextval(%')")) {
                    statement.execute("select setval(pg_get_serial_sequence('" + quote(seed) + "', '" + column + "'), "
                        + "coalesce((select max(" + quote(column) + ") from " + quote(seed) + "), 0) + 1, false)");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("테스트 DB를 비우지 못했습니다", e);
        }
    }

    private static boolean seedSaved(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery(
            "select 1 from information_schema.schemata where schema_name = '" + SEED_SCHEMA + "'")) {
            return rs.next();
        }
    }

    private static void saveSeed(Statement statement, List<String> tables) throws SQLException {
        statement.execute("create schema " + SEED_SCHEMA);
        for (String table : tables) {
            boolean hasRows;
            try (ResultSet rs = statement.executeQuery("select exists (select 1 from " + quote(table) + ")")) {
                hasRows = rs.next() && rs.getBoolean(1);
            }
            if (hasRows) {
                statement.execute("create table " + SEED_SCHEMA + "." + quote(table) + " as table " + quote(table));
            }
        }
    }

    private static List<String> queryList(Statement statement, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static String quoteAll(List<String> names) {
        return String.join(", ", names.stream().map(ResetDataBeforeClassListener::quote).toList());
    }

    private static String quote(String name) {
        return '"' + name + '"';
    }

    /** spring-data-redis가 없는 모듈에서도 이 리스너를 불러올 수 있게 Redis 코드를 따로 둔다 */
    private static final class RedisReset {

        static void flushAll(ApplicationContext context) {
            context.getBeanProvider(org.springframework.data.redis.connection.RedisConnectionFactory.class)
                .ifAvailable(factory -> {
                    try (var connection = factory.getConnection()) {
                        connection.serverCommands().flushAll();
                    } catch (RuntimeException e) {
                        // Redis 자동 설정은 있지만 테스트가 SharedRedis를 붙이지 않은 모듈 (접속할 Redis가 없음 → 비울 것도 없음)
                    }
                });
        }
    }
}
