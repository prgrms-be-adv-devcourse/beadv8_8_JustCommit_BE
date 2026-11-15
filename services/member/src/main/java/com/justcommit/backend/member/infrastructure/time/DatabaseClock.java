package com.justcommit.backend.member.infrastructure.time;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * DB 서버의 현재 시각 (#409). Access 발급 시각(iat · exp)과 이후 폐기 시각을 같은 곳(DB)에서 읽기 위해 쓴다.
 * - clock_timestamp(): 호출한 순간의 시각. now() · CURRENT_TIMESTAMP는 트랜잭션 시작 시각이라
 *   잠금 대기 전 값일 수 있어 쓰지 않는다
 * - JPA와 같은 DataSource를 쓰고, 트랜잭션 관리자가 JDBC 접근에 같은 연결을 공유하는 구성에서
 *   진행 중인 트랜잭션의 연결로 실행된다 (DatabaseClockIntegrationTest로 확인)
 * - 호출 조건: 기존 회원은 회원 잠금과 검사를 마친 뒤 호출한다.
 *   소셜 가입은 새 회원 저장 후, 같은 가입 트랜잭션에서 호출한다
 * - 시각을 읽는 곳을 통일할 뿐, DB 시계의 역행 · 장애 전환까지 순서를 보장하지는 않는다
 */
@Component
@RequiredArgsConstructor
public class DatabaseClock {

  private static final String CLOCK_SQL = "select clock_timestamp()";

  private final JdbcTemplate jdbcTemplate;

  public Instant now() {
    OffsetDateTime now = jdbcTemplate.queryForObject(CLOCK_SQL, OffsetDateTime.class);
    if (now == null) {
      throw new IllegalStateException("DB 시각을 읽지 못했습니다.");
    }
    return now.toInstant();
  }
}
