package com.justcommit.backend.payment.application.member;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.payment.application.WalletService;
import com.justcommit.backend.payment.config.JpaAuditingConfig;
import com.justcommit.backend.payment.domain.MemberReplica;
import com.justcommit.backend.payment.domain.WalletType;
import com.justcommit.backend.payment.infrastructure.repository.MemberReplicaRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// 회원 이벤트 반영(#387)을 Flyway 스키마로 확인한다: 조건부 upsert와 지갑 on conflict do nothing
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, MemberReplicaService.class, WalletService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MemberReplicaServiceIntegrationTest {

    private static final Long MEMBER_ID = 50L;
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-20T10:00:00+09:00");
    private static final OffsetDateTime T2 = T1.plusMinutes(1);

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Autowired MemberReplicaService memberReplicaService;
    @Autowired WalletService walletService;
    @Autowired MemberReplicaRepository memberReplicaRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        memberReplicaRepository.deleteAllInBatch();
        jdbcTemplate.update("delete from wallet where wallet_type = 'MEMBER'");
    }

    @Test
    @DisplayName("같은 MemberCreated를 두 번 받아도 복제본 1행, 회원 지갑 1개 (잔액 0, 미수금 0)")
    void sameCreatedTwiceKeepsOneReplicaAndWallet() {
        memberReplicaService.applyCreated(MEMBER_ID, "초록손", "MEMBER", false, T1);
        memberReplicaService.applyCreated(MEMBER_ID, "초록손", "MEMBER", false, T1);

        assertThat(memberReplicaRepository.count()).isEqualTo(1);
        assertThat(memberWalletCount()).isEqualTo(1);
        assertThat(walletRepository.findByMemberId(MEMBER_ID)).get().satisfies(wallet -> {
            assertThat(wallet.getWalletType()).isEqualTo(WalletType.MEMBER);
            assertThat(wallet.getBalance()).isEqualByComparingTo("0");
            assertThat(wallet.getReceivable()).isEqualByComparingTo("0");
            assertThat(wallet.getVersion()).isZero();
        });
    }

    @Test
    @DisplayName("지갑이 이미 있는 회원의 MemberCreated는 지갑을 그대로 둔다 (잔액 유지)")
    void keepsExistingWallet() {
        walletService.createIfAbsent(MEMBER_ID);
        jdbcTemplate.update("update wallet set balance = 5000 where member_id = ?", MEMBER_ID);

        memberReplicaService.applyCreated(MEMBER_ID, "초록손", "MEMBER", false, T1);

        assertThat(memberWalletCount()).isEqualTo(1);
        assertThat(walletRepository.findByMemberId(MEMBER_ID).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("5000"));
    }

    @Test
    @DisplayName("저장된 값보다 오래된 이벤트는 무시한다")
    void ignoresOlderEvent() {
        memberReplicaService.applyChanged(MEMBER_ID, "새이름", "MEMBER", true, T2);
        memberReplicaService.applyCreated(MEMBER_ID, "옛이름", "MEMBER", false, T1);

        MemberReplica replica = memberReplicaRepository.findById(MEMBER_ID).orElseThrow();
        assertThat(replica.getNickname()).isEqualTo("새이름");
        assertThat(replica.isSeller()).isTrue();
        assertThat(replica.getLastEventAt().toInstant()).isEqualTo(T2.toInstant());
    }

    @Test
    @DisplayName("탈퇴 표시 뒤 늦게 온 가입 이벤트는 복제본을 되살리지 않는다")
    void withdrawnIsNotRevived() {
        memberReplicaService.applyWithdrawn(MEMBER_ID, T2);
        memberReplicaService.applyCreated(MEMBER_ID, "초록손", "MEMBER", false, T1);

        MemberReplica replica = memberReplicaRepository.findById(MEMBER_ID).orElseThrow();
        assertThat(replica.isWithdrawn()).isTrue();
        assertThat(replica.getNickname()).isNull();
    }

    @Test
    @DisplayName("정보 변경 이벤트가 와도 탈퇴 표시는 그대로")
    void updateKeepsWithdrawn() {
        memberReplicaService.applyCreated(MEMBER_ID, "초록손", "MEMBER", false, T1);
        memberReplicaService.applyWithdrawn(MEMBER_ID, T2);
        memberReplicaService.applyChanged(MEMBER_ID, "새이름", "MEMBER", false, T2.plusMinutes(1));

        assertThat(memberReplicaRepository.findById(MEMBER_ID).orElseThrow().isWithdrawn()).isTrue();
    }

    private int memberWalletCount() {
        return jdbcTemplate.queryForObject("select count(*) from wallet where member_id = ?", Integer.class, MEMBER_ID);
    }
}
