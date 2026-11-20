package com.justcommit.backend.payment.application.withdrawal;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.payment.config.JpaAuditingConfig;
import com.justcommit.backend.payment.domain.CashOut;
import com.justcommit.backend.payment.domain.CashOutStatus;
import com.justcommit.backend.payment.domain.Charge;
import com.justcommit.backend.payment.domain.ChargeStatus;
import com.justcommit.backend.payment.domain.ChargeType;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.infrastructure.repository.CashOutRepository;
import com.justcommit.backend.payment.infrastructure.repository.ChargeRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.presentation.internal.dto.WithdrawalBlockingReason;
import com.justcommit.backend.payment.presentation.internal.dto.WithdrawalCheckResponse;
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
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// 탈퇴 확인(#329)을 Flyway 스키마와 실제 PostgreSQL에서 확인한다 (판정 기준 · exists 쿼리 · 지갑 없는 회원)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, WithdrawalCheckService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WithdrawalCheckIntegrationTest {

    private static final Long MEMBER_ID = 329L;
    private static final Long OTHER_MEMBER_ID = 330L;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Autowired WithdrawalCheckService withdrawalCheckService;
    @Autowired WalletRepository walletRepository;
    @Autowired ChargeRepository chargeRepository;
    @Autowired CashOutRepository cashOutRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private int cashOutSeq = 0;

    @AfterEach
    void cleanUp() {
        cashOutRepository.deleteAllInBatch();
        chargeRepository.deleteAllInBatch();
        jdbcTemplate.update("delete from wallet where wallet_type = 'MEMBER'");
    }

    @Test
    @DisplayName("잔액 0 · 진행 중 거래 없음 · 미수금 0이면 차단하지 않는다")
    void noBlocker() {
        saveWallet(MEMBER_ID, "0", "0");

        WithdrawalCheckResponse result = withdrawalCheckService.check(MEMBER_ID);

        assertNotBlocked(result);
    }

    @Test
    @DisplayName("잔액만 남아 있으면 WALLET_BALANCE_REMAINING")
    void balanceOnly() {
        saveWallet(MEMBER_ID, "15000", "0");

        WithdrawalCheckResponse result = withdrawalCheckService.check(MEMBER_ID);

        assertThat(result.hasWithdrawalBlocker()).isTrue();
        assertThat(result.blockingReasons()).containsExactly(WithdrawalBlockingReason.WALLET_BALANCE_REMAINING);
        assertDetails(result, "15000", false, "0");
    }

    @Test
    @DisplayName("PENDING 충전이 있으면 TRANSACTION_IN_PROGRESS")
    void pendingCharge() {
        Wallet wallet = saveWallet(MEMBER_ID, "0", "0");
        saveCharge(wallet, ChargeStatus.PENDING);

        assertInProgressOnly(withdrawalCheckService.check(MEMBER_ID));
    }

    @Test
    @DisplayName("REVIEW_REQUIRED 충전이 있으면 TRANSACTION_IN_PROGRESS")
    void reviewRequiredCharge() {
        Wallet wallet = saveWallet(MEMBER_ID, "0", "0");
        saveCharge(wallet, ChargeStatus.REVIEW_REQUIRED);

        assertInProgressOnly(withdrawalCheckService.check(MEMBER_ID));
    }

    @Test
    @DisplayName("READY 충전과 끝난 충전(APPROVED · FAILED · CANCELED)만 있으면 차단하지 않는다")
    void readyAndFinishedChargesDoNotBlock() {
        Wallet wallet = saveWallet(MEMBER_ID, "0", "0");
        saveCharge(wallet, ChargeStatus.READY);
        saveCharge(wallet, ChargeStatus.APPROVED);
        saveCharge(wallet, ChargeStatus.FAILED);
        saveCharge(wallet, ChargeStatus.CANCELED);

        assertNotBlocked(withdrawalCheckService.check(MEMBER_ID));
    }

    @Test
    @DisplayName("PENDING 인출이 있으면 TRANSACTION_IN_PROGRESS (은행 지급 실패로 남은 건 포함)")
    void pendingCashOut() {
        Wallet wallet = saveWallet(MEMBER_ID, "0", "0");
        saveCashOut(wallet, CashOutStatus.PENDING);

        assertInProgressOnly(withdrawalCheckService.check(MEMBER_ID));
    }

    @Test
    @DisplayName("COMPLETED 인출만 있으면 차단하지 않는다")
    void completedCashOutDoesNotBlock() {
        Wallet wallet = saveWallet(MEMBER_ID, "0", "0");
        saveCashOut(wallet, CashOutStatus.COMPLETED);

        assertNotBlocked(withdrawalCheckService.check(MEMBER_ID));
    }

    @Test
    @DisplayName("미수금만 남아 있으면 SELLER_DEBT_REMAINING")
    void receivableOnly() {
        saveWallet(MEMBER_ID, "0", "2500");

        WithdrawalCheckResponse result = withdrawalCheckService.check(MEMBER_ID);

        assertThat(result.hasWithdrawalBlocker()).isTrue();
        assertThat(result.blockingReasons()).containsExactly(WithdrawalBlockingReason.SELLER_DEBT_REMAINING);
        assertDetails(result, "0", false, "2500");
    }

    @Test
    @DisplayName("잔액 · 진행 중 거래 · 미수금이 함께 있으면 사유 세 개")
    void multipleReasons() {
        Wallet wallet = saveWallet(MEMBER_ID, "10000", "3000");
        saveCharge(wallet, ChargeStatus.PENDING);
        saveCashOut(wallet, CashOutStatus.PENDING);

        WithdrawalCheckResponse result = withdrawalCheckService.check(MEMBER_ID);

        assertThat(result.hasWithdrawalBlocker()).isTrue();
        assertThat(result.blockingReasons()).containsExactly(
                WithdrawalBlockingReason.WALLET_BALANCE_REMAINING,
                WithdrawalBlockingReason.TRANSACTION_IN_PROGRESS,
                WithdrawalBlockingReason.SELLER_DEBT_REMAINING);
        assertDetails(result, "10000", true, "3000");
    }

    @Test
    @DisplayName("다른 회원 지갑의 진행 중 거래는 보지 않는다")
    void otherMembersTransactionsAreIgnored() {
        saveWallet(MEMBER_ID, "0", "0");
        Wallet other = saveWallet(OTHER_MEMBER_ID, "0", "0");
        saveCharge(other, ChargeStatus.PENDING);
        saveCashOut(other, CashOutStatus.PENDING);

        assertNotBlocked(withdrawalCheckService.check(MEMBER_ID));
    }

    @Test
    @DisplayName("지갑이 없는 회원은 차단 사유 없음으로 응답하고 지갑을 만들지 않는다")
    void noWallet() {
        WithdrawalCheckResponse result = withdrawalCheckService.check(MEMBER_ID);

        assertNotBlocked(result);
        assertThat(walletRepository.findByMemberId(MEMBER_ID)).isEmpty();
    }

    private static void assertNotBlocked(WithdrawalCheckResponse result) {
        assertThat(result.hasWithdrawalBlocker()).isFalse();
        assertThat(result.blockingReasons()).isNotNull().isEmpty();
        assertDetails(result, "0", false, "0");
    }

    private static void assertInProgressOnly(WithdrawalCheckResponse result) {
        assertThat(result.hasWithdrawalBlocker()).isTrue();
        assertThat(result.blockingReasons()).containsExactly(WithdrawalBlockingReason.TRANSACTION_IN_PROGRESS);
        assertDetails(result, "0", true, "0");
    }

    private static void assertDetails(WithdrawalCheckResponse result, String balance, boolean pending, String debt) {
        assertThat(result.details().walletBalance()).isEqualByComparingTo(balance);
        assertThat(result.details().hasPendingTransactions()).isEqualTo(pending);
        assertThat(result.details().outstandingDebt()).isEqualByComparingTo(debt);
    }

    private Wallet saveWallet(Long memberId, String balance, String receivable) {
        Wallet wallet = Wallet.forMember(memberId);
        if (new BigDecimal(balance).signum() > 0) {
            wallet.credit(new BigDecimal(balance));
        }
        if (new BigDecimal(receivable).signum() > 0) {
            wallet.increaseReceivable(new BigDecimal(receivable));
        }
        return walletRepository.saveAndFlush(wallet);
    }

    // 엔티티로 READY를 만든 뒤 SQL로 상태를 맞춘다 (REVIEW_REQUIRED · CANCELED로 바꾸는 엔티티 메서드가 없음)
    private void saveCharge(Wallet wallet, ChargeStatus status) {
        Charge charge = chargeRepository.saveAndFlush(Charge.ready(wallet, ChargeType.TOSS, new BigDecimal("10000")));
        jdbcTemplate.update("update charge set status = ? where id = ?", status.name(), charge.getId());
    }

    private void saveCashOut(Wallet wallet, CashOutStatus status) {
        CashOut cashOut = cashOutRepository.saveAndFlush(CashOut.pending(wallet.getId(), new BigDecimal("5000"),
                "wd-key-" + (++cashOutSeq), "004", "enc", "4444", "홍길동", LocalDateTime.now()));
        if (status == CashOutStatus.COMPLETED) {
            jdbcTemplate.update("update cash_out set status = 'COMPLETED', completed_at = localtimestamp where id = ?",
                    cashOut.getId());
        }
    }
}
