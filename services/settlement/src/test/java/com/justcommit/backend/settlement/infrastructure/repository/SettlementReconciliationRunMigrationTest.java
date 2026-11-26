package com.justcommit.backend.settlement.infrastructure.repository;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// V7 마이그레이션을 빈 DB에 실제로 적용해 기준 행(BASELINE)이 배포 시각으로 한 번 들어가는지 확인한다.
// 스프링 테스트들은 BeforeEach에서 실행 이력을 비우므로 여기서 Flyway만으로 따로 본다
@Testcontainers(disabledWithoutDocker = true)
class SettlementReconciliationRunMigrationTest {

    @Test
    @DisplayName("V7은 배포 시각으로 BASELINE 행 1개를 넣고, 그 시각이 미실행 알림의 기준 시각이 된다")
    void insertsBaselineOnMigrate() {
        LocalDateTime before = LocalDateTime.now();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(SharedPostgres.createDatabase(),
                SharedPostgres.container().getUsername(), SharedPostgres.container().getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("select * from settlement_reconciliation_run");

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("result")).isEqualTo("BASELINE");
            assertThat(row.get("target_count")).isEqualTo(0);
            assertThat(row.get("mismatch_count")).isEqualTo(0);
            assertThat(row.get("failed_batch_count")).isEqualTo(0);
        });
        LocalDateTime baselineAt = new SettlementReconciliationRunRepository(jdbcTemplate)
                .findLastSuccessOrBaselineAt().orElseThrow();
        // 세션 시간대(JVM 시간대)의 배포 시각
        assertThat(baselineAt).isCloseTo(before, within(1, java.time.temporal.ChronoUnit.MINUTES));
    }
}
