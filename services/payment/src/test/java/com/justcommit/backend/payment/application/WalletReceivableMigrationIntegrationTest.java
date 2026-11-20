package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

// V2까지 적용된 DB의 지갑 · 거래 행이 V3 적용 뒤 미수금 0으로 채워지는지 확인한다
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WalletReceivableMigrationIntegrationTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Autowired DataSource dataSource;

    @Test
    @DisplayName("V3 적용 전부터 있던 지갑의 receivable과 거래 행의 receivable_after는 0이다")
    void existingRowsGetZeroReceivable() {
        migrate("2");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long walletId = jdbc.queryForObject("""
                insert into wallet (wallet_type, member_id, balance, version, created_at, updated_at)
                values ('MEMBER', 1, 5000, 0, now(), now()) returning id
                """, Long.class);
        Long transactionId = jdbc.queryForObject("""
                insert into wallet_transaction (wallet_id, ref_type, ref_id, ref_occurred_time, status, amount, balance_after)
                values (?, 'SETTLEMENT', 1, now(), 'IN', 5000, 5000) returning id
                """, Long.class, walletId);

        migrate("latest");

        assertThat(jdbc.queryForObject("select receivable from wallet where id = ?", BigDecimal.class, walletId))
                .isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select receivable_after from wallet_transaction where id = ?",
                BigDecimal.class, transactionId))
                .isEqualByComparingTo("0");
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }
}
