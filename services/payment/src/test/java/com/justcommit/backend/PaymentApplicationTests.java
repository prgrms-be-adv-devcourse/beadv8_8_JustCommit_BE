package com.justcommit.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.justcommit.backend.payment.domain.WalletType;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PaymentApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    // 테스트할 때마다 새 RSA 키를 만들어 공개키만 넣는다 (키를 파일에 남기지 않음)
    @DynamicPropertySource
    static void jwtPublicKey(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", PaymentApplicationTests::newPublicKey);
    }

    @Test
    @DisplayName("빈 DB에서 Flyway V1이 적용되고 ddl-auto validate로 payment 서비스가 뜬다")
    void contextLoads(@Autowired Flyway flyway, @Autowired WalletRepository walletRepository) {
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion(), MigrationInfo::getState)
                .contains(tuple("1", MigrationState.SUCCESS));

        // 시스템 지갑은 V1이 종류별로 하나씩 만든다
        assertThat(walletRepository.findByWalletType(WalletType.HOLDING)).isPresent();
        assertThat(walletRepository.findByWalletType(WalletType.REVENUE)).isPresent();
    }

    // 엔티티 생성 메서드를 거치지 않고 SQL로 직접 넣어 DB 제약만 확인한다
    @Test
    @DisplayName("회원 지갑은 member_id가 꼭 있고, 시스템 지갑은 member_id를 가질 수 없다 (ck_wallet_member_id)")
    void rejectsWalletWithWrongMemberId(@Autowired JdbcTemplate jdbcTemplate) {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into wallet (wallet_type, member_id, balance, version) values ('MEMBER', null, 0, 0)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_wallet_member_id");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into wallet (wallet_type, member_id, balance, version) values ('REVENUE', 1, 0, 0)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_wallet_member_id");
    }

    @Test
    @DisplayName("시스템 지갑은 종류별로 하나만 만들 수 있다 (uk_wallet_system_type)")
    void rejectsSecondSystemWalletOfSameType(@Autowired JdbcTemplate jdbcTemplate) {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into wallet (wallet_type, member_id, balance, version) values ('HOLDING', null, 0, 0)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_wallet_system_type");
    }

    private static String newPublicKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
