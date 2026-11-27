package com.justcommit.backend.member.support;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * 테스트 클래스가 시작하기 전에 DB 테이블과 Redis를 비운다 (#664).
 *
 * <p>{@link MemberIntegrationTest}를 상속한 클래스들은 컨텍스트 · DB를 같이 쓰므로, 앞 클래스가 남긴 데이터를 여기서 지운다.
 * {@code flyway_schema_history}는 남긴다 (스키마는 컨텍스트가 뜰 때 한 번만 만든다).
 * member 마이그레이션에는 기본 데이터(INSERT)가 없어 모든 테이블을 비워도 된다.
 */
public class ResetDataBeforeClassListener extends AbstractTestExecutionListener {

  @Override
  public int getOrder() {
    // 의존성 주입(2000)보다 먼저, 컨텍스트를 처음 띄우는 시점과 같게
    return 1500;
  }

  @Override
  public void beforeTestClass(TestContext testContext) {
    ApplicationContext context = testContext.getApplicationContext();
    JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
    List<String> tables = jdbcTemplate.queryForList(
        "select tablename from pg_tables where schemaname = current_schema() and tablename <> 'flyway_schema_history'",
        String.class);
    if (!tables.isEmpty()) {
      String names = tables.stream().map(name -> '"' + name + '"').collect(Collectors.joining(", "));
      jdbcTemplate.execute("truncate table " + names + " restart identity cascade");
    }
    context.getBeanProvider(StringRedisTemplate.class).ifAvailable(redis ->
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
          connection.serverCommands().flushAll();
          return null;
        }));
  }
}
