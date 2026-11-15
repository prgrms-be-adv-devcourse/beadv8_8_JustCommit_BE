package com.justcommit.backend.payment.application.recovery;

import com.justcommit.backend.payment.infrastructure.repository.ChargeRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// V1만 적용된 DB에 남은 PENDING 충전이 V2 적용 뒤 복구 스케줄러 대상이 되는지 확인한다
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChargeRecoveryMigrationIntegrationTest {

    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 11, 15, 10, 0);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired DataSource dataSource;
    @Autowired ChargeRepository chargeRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("V2 적용 전부터 PENDING이던 충전은 next_attempt_at = updated_at으로 채워져 조회 대상이 된다")
    void existingPendingChargeBecomesRecoveryTarget() {
        migrate("1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long walletId = jdbc.queryForObject("""
                insert into wallet (wallet_type, member_id, balance, version, created_at, updated_at)
                values ('MEMBER', 1, 0, 0, now(), now()) returning id
                """, Long.class);
        Long pendingId = insertCharge(jdbc, walletId, "CHG-pending", "PENDING");
        Long readyId = insertCharge(jdbc, walletId, "CHG-ready", "READY");
        Long approvedId = insertCharge(jdbc, walletId, "CHG-approved", "APPROVED");

        migrate("latest");

        assertThat(jdbc.queryForObject("select next_attempt_at from charge where id = ?", LocalDateTime.class, pendingId))
                .isEqualTo(UPDATED_AT);
        assertThat(jdbc.queryForObject("select attempt_count from charge where id = ?", Integer.class, pendingId))
                .isZero();
        assertThat(jdbc.queryForObject("select next_attempt_at from charge where id = ?", LocalDateTime.class, readyId))
                .isNull();

        // 복구 스케줄러의 확보 쿼리(SKIP LOCKED)가 PENDING 건만 집는다
        Long claimed = new TransactionTemplate(transactionManager).execute(status ->
                chargeRepository.lockNextRecoveryTarget(UPDATED_AT).orElse(null));
        assertThat(claimed).isEqualTo(pendingId).isNotIn(readyId, approvedId);
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private Long insertCharge(JdbcTemplate jdbc, Long walletId, String orderNo, String status) {
        return jdbc.queryForObject("""
                insert into charge (wallet_id, charge_type, pg_order_no, amount, status, created_at, updated_at)
                values (?, 'TOSS', ?, 10000, ?, ?, ?) returning id
                """, Long.class, walletId, orderNo, status, UPDATED_AT.minusMinutes(1), UPDATED_AT);
    }
}
