package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

// 진짜 DB로 지갑 생성을 확인한다 (readOnly 트랜잭션에서 저장이 막히는 문제는 목으로는 잡히지 않음)
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.check_nullability=false"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, WalletService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WalletServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired WalletService walletService;
    @Autowired WalletRepository walletRepository;

    @AfterEach
    void cleanUp() {
        walletRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("지갑이 없는 회원이면 잔액 0인 지갑을 생성한다")
    void createsWalletWhenAbsent() {
        Long memberId = 1L;

        walletService.createIfAbsent(memberId);

        Wallet wallet = walletRepository.findByMemberId(memberId).orElseThrow();
        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("이미 지갑이 있으면 새로 만들지 않고 기존 잔액도 그대로 둔다")
    void keepsExistingWallet() {
        Long memberId = 2L;
        Wallet existing = new Wallet(memberId);
        existing.credit(new BigDecimal("5000"));
        walletRepository.save(existing);

        walletService.createIfAbsent(memberId);

        assertThat(walletCountOf(memberId)).isEqualTo(1);
        assertThat(walletRepository.findByMemberId(memberId).orElseThrow().getBalance())
                .isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("같은 회원으로 두 번 호출해도 지갑은 하나만 생긴다")
    void createsOnlyOneWalletWhenCalledTwice() {
        Long memberId = 3L;

        walletService.createIfAbsent(memberId);
        walletService.createIfAbsent(memberId);

        assertThat(walletCountOf(memberId)).isEqualTo(1);
    }

    private long walletCountOf(Long memberId) {
        return walletRepository.findAll().stream()
                .filter(wallet -> wallet.getMemberId().equals(memberId))
                .count();
    }
}