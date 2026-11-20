package com.justcommit.backend.payment.application.cashout;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.payment.client.member.MemberAccount;
import com.justcommit.backend.payment.client.member.MemberAccountClient;
import com.justcommit.backend.payment.config.ClockConfig;
import com.justcommit.backend.payment.config.JpaAuditingConfig;
import com.justcommit.backend.payment.domain.CashOut;
import com.justcommit.backend.payment.domain.CashOutStatus;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.bank.BankTransferClient;
import com.justcommit.backend.payment.infrastructure.bank.BankTransferRequest;
import com.justcommit.backend.payment.infrastructure.bank.BankTransferResult;
import com.justcommit.backend.payment.infrastructure.crypto.AccountNoCipher;
import com.justcommit.backend.payment.infrastructure.crypto.PaymentKeyCipher;
import com.justcommit.backend.payment.infrastructure.repository.CashOutRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 인출 신청(#387)을 Flyway 스키마와 실제 행 잠금으로 확인한다. member 계좌 조회와 은행은 가짜로 바꾼다
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, AccountNoCipher.class, PaymentKeyCipher.class,
        CashOutService.class, CashOutFacade.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
class CashOutIntegrationTest {

    private static final Long MEMBER_ID = 40L;
    private static final String ACCOUNT_NO = "11122233334444";
    private static final MemberAccount ACCOUNT = new MemberAccount("004", ACCOUNT_NO, "홍길동");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Autowired CashOutFacade cashOutFacade;
    @Autowired CashOutService cashOutService;
    @Autowired CashOutRepository cashOutRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired AccountNoCipher accountNoCipher;
    @Autowired PaymentKeyCipher paymentKeyCipher;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean MemberAccountClient memberAccountClient;
    @MockitoBean BankTransferClient bankTransferClient;

    @BeforeEach
    void setUpDoubles() {
        given(memberAccountClient.getAccount(MEMBER_ID)).willReturn(ACCOUNT);
        given(bankTransferClient.transfer(any()))
                .willAnswer(invocation -> success(invocation.getArgument(0)));
    }

    @AfterEach
    void cleanUp() {
        cashOutRepository.deleteAllInBatch();
        walletTransactionRepository.deleteAllInBatch();
        jdbcTemplate.update("delete from wallet where wallet_type = 'MEMBER'");
    }

    // ===== 성공 =====

    @Test
    @DisplayName("한도 안의 인출: 잔액 차감, 인출 건 COMPLETED, CASH_OUT 원장 1행, 계좌는 암호문으로만 저장")
    void cashOutSucceeds() {
        saveWallet("10000");

        CashOutResult result = cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(CashOutStatus.COMPLETED);
        assertThat(result.amount()).isEqualByComparingTo("3000");
        assertThat(result.accountLast4()).isEqualTo("4444");
        assertThat(result.balanceAfter()).isEqualByComparingTo("7000");
        assertThat(balance()).isEqualByComparingTo("7000");

        CashOut cashOut = cashOutRepository.findById(result.cashOutId()).orElseThrow();
        assertThat(cashOut.getStatus()).isEqualTo(CashOutStatus.COMPLETED);
        assertThat(cashOut.getCompletedAt()).isNotNull();
        assertThat(cashOut.getAccountNoEnc()).doesNotContain(ACCOUNT_NO);
        assertThat(accountNoCipher.decrypt(cashOut.getAccountNoEnc())).isEqualTo(ACCOUNT_NO);
        // 계좌번호는 결제키와 다른 키로 암호화한다
        assertThatThrownBy(() -> paymentKeyCipher.decrypt(cashOut.getAccountNoEnc()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cashOut.getBankCode()).isEqualTo("004");
        assertThat(cashOut.getAccountHolder()).isEqualTo("홍길동");

        List<WalletTransaction> rows = walletTransactionRepository.findAll();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getRefType()).isEqualTo(RefType.CASH_OUT);
            assertThat(row.getRefId()).isEqualTo(result.cashOutId());
            assertThat(row.getStatus()).isEqualTo(TransactionStatus.OUT);
            assertThat(row.getAmount()).isEqualByComparingTo("3000");
            assertThat(row.getBalanceAfter()).isEqualByComparingTo("7000");
        });

        verify(bankTransferClient).transfer(any());
    }

    @Test
    @DisplayName("잔액 전체를 인출할 수 있다 (한도 = 잔액)")
    void cashOutWholeBalance() {
        saveWallet("10000");

        CashOutResult result = cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("10000"));

        assertThat(result.balanceAfter()).isEqualByComparingTo("0");
        assertThat(balance()).isEqualByComparingTo("0");
    }

    // ===== 거절: 잔액, 원장, 인출 건 변화 없음 =====

    @Test
    @DisplayName("잔액보다 많이 인출하면 한도 초과로 거절하고 지급하지 않는다")
    void rejectsOverBalance() {
        saveWallet("10000");

        assertRejected(() -> cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("10001")),
                PaymentErrorCode.CASH_OUT_LIMIT_EXCEEDED);
        assertNothingChanged("10000");
    }

    @Test
    @DisplayName("[S-G05] 미수금이 남아 있으면 잔액이 있어도 인출을 거절한다")
    void rejectsWhenReceivableRemains() {
        saveWallet("10000");
        jdbcTemplate.update("update wallet set receivable = 500 where member_id = ?", MEMBER_ID);

        assertRejected(() -> cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("1000")),
                PaymentErrorCode.RECEIVABLE_REMAINING);
        assertNothingChanged("10000");
    }

    @ParameterizedTest(name = "금액 {0}")
    @ValueSource(strings = {"0", "-1000", "1000.5"})
    @DisplayName("금액이 0 이하거나 원 단위가 아니면 계좌 조회 전에 거절한다")
    void rejectsInvalidAmount(String amount) {
        saveWallet("10000");

        assertRejected(() -> cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal(amount)),
                PaymentErrorCode.INVALID_AMOUNT);
        assertNothingChanged("10000");
        verify(memberAccountClient, never()).getAccount(anyLong());
    }

    @Test
    @DisplayName("지갑이 없으면 잔액 0으로 보고 한도 초과로 거절하고, 지갑을 만들지 않는다")
    void rejectsWithoutWallet() {
        assertRejected(() -> cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("1000")),
                PaymentErrorCode.CASH_OUT_LIMIT_EXCEEDED);

        assertThat(walletRepository.findByMemberId(MEMBER_ID)).isEmpty();
        assertThat(cashOutRepository.count()).isZero();
        verify(bankTransferClient, never()).transfer(any());
    }

    @Test
    @DisplayName("계좌가 없으면 차감 전에 거절한다")
    void rejectsWithoutAccount() {
        saveWallet("10000");
        given(memberAccountClient.getAccount(MEMBER_ID))
                .willThrow(new BusinessException(PaymentErrorCode.CASH_OUT_ACCOUNT_NOT_FOUND));

        assertRejected(() -> cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("1000")),
                PaymentErrorCode.CASH_OUT_ACCOUNT_NOT_FOUND);
        assertNothingChanged("10000");
    }

    @Test
    @DisplayName("계좌 조회가 실패하면 차감 전에 거절한다")
    void rejectsWhenAccountLookupFails() {
        saveWallet("10000");
        given(memberAccountClient.getAccount(MEMBER_ID))
                .willThrow(new BusinessException(PaymentErrorCode.ACCOUNT_LOOKUP_FAILED));

        assertRejected(() -> cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("1000")),
                PaymentErrorCode.ACCOUNT_LOOKUP_FAILED);
        assertNothingChanged("10000");
    }

    @ParameterizedTest(name = "키 길이 {0}")
    @ValueSource(ints = {0, 65})
    @DisplayName("Idempotency-Key가 비었거나 64자를 넘으면 거절한다")
    void rejectsInvalidIdempotencyKey(int length) {
        saveWallet("10000");

        assertRejected(() -> cashOutFacade.requestCashOut(MEMBER_ID, "k".repeat(length), new BigDecimal("1000")),
                PaymentErrorCode.INVALID_IDEMPOTENCY_KEY);
        assertNothingChanged("10000");
    }

    // ===== 은행 예외 =====

    @Test
    @DisplayName("은행 지급이 예외면 잔액은 차감된 채 인출 건 PENDING으로 남고, 로그에 계좌 원문이 없다")
    void bankFailureKeepsPending(CapturedOutput output) {
        saveWallet("10000");
        // 이미 걸린 응답이 다시 불리지 않게 willThrow().given() 형태로 바꾼다
        willThrow(new IllegalStateException("bank down: account " + ACCOUNT_NO))
                .given(bankTransferClient).transfer(any());

        CashOutResult result = cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(CashOutStatus.PENDING);
        assertThat(balance()).isEqualByComparingTo("7000");
        CashOut cashOut = cashOutRepository.findById(result.cashOutId()).orElseThrow();
        assertThat(cashOut.getStatus()).isEqualTo(CashOutStatus.PENDING);
        assertThat(cashOut.getCompletedAt()).isNull();
        assertThat(walletTransactionRepository.count()).isEqualTo(1);

        assertThat(output).contains("인출 지급 실패").doesNotContain(ACCOUNT_NO);
    }

    // ===== 멱등 =====

    @Test
    @DisplayName("같은 키로 다시 요청하면 처음 결과를 돌려주고 계좌 조회와 지급을 다시 하지 않는다")
    void sameKeyReturnsFirstResult() {
        saveWallet("10000");

        CashOutResult first = cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("3000"));
        CashOutResult second = cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("3000"));

        assertThat(second).isEqualTo(first);
        assertThat(balance()).isEqualByComparingTo("7000");
        assertThat(cashOutRepository.count()).isEqualTo(1);
        verify(memberAccountClient, times(1)).getAccount(MEMBER_ID);
        verify(bankTransferClient, times(1)).transfer(any());
    }

    @Test
    @DisplayName("같은 키에 다른 금액이면 409 IDEMPOTENCY_KEY_CONFLICT, 잔액과 인출 건은 그대로")
    void sameKeyDifferentAmountConflicts() {
        saveWallet("10000");
        cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("3000"));

        assertRejected(() -> cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("4000")),
                PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT);

        assertThat(balance()).isEqualByComparingTo("7000");
        assertThat(cashOutRepository.count()).isEqualTo(1);
        verify(memberAccountClient, times(1)).getAccount(MEMBER_ID);
    }

    // ===== 동시성 =====

    @Test
    @DisplayName("같은 키로 동시에 8번 요청해도 인출 건 1개, 차감 1번, 지급 1번")
    void concurrentSameKeyDebitsOnce() throws Exception {
        saveWallet("10000");

        List<CashOutResult> results = runConcurrently(8,
                i -> () -> cashOutFacade.requestCashOut(MEMBER_ID, "same-key", new BigDecimal("3000")));

        assertThat(results).hasSize(8).extracting(CashOutResult::cashOutId).containsOnly(results.getFirst().cashOutId());
        assertThat(balance()).isEqualByComparingTo("7000");
        assertThat(cashOutRepository.count()).isEqualTo(1);
        assertThat(walletTransactionRepository.count()).isEqualTo(1);
        verify(bankTransferClient, times(1)).transfer(any());
    }

    @Test
    @DisplayName("다른 키 2건의 합이 잔액을 넘으면 하나만 성공하고 잔액은 음수가 되지 않는다")
    void concurrentDifferentKeysCannotOverdraw() throws Exception {
        saveWallet("10000");

        List<Object> outcomes = runConcurrentlyCatching(2,
                i -> () -> cashOutFacade.requestCashOut(MEMBER_ID, "key-" + i, new BigDecimal("6000")));

        assertThat(outcomes).filteredOn(CashOutResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(BusinessException.class::isInstance)
                .singleElement()
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.CASH_OUT_LIMIT_EXCEEDED);
        assertThat(balance()).isEqualByComparingTo("4000");
        assertThat(cashOutRepository.count()).isEqualTo(1);
    }

    // ===== 트랜잭션, 잠금 밖 호출 =====

    @Test
    @DisplayName("member 계좌 조회와 은행 지급 중에는 트랜잭션이 없고 회원 지갑 잠금도 잡고 있지 않다")
    void externalCallsRunOutsideTransactionAndLock() {
        saveWallet("10000");
        AtomicBoolean memberCallChecked = new AtomicBoolean();
        AtomicBoolean bankCallChecked = new AtomicBoolean();

        given(memberAccountClient.getAccount(MEMBER_ID)).willAnswer(invocation -> {
            assertNoTransactionAndWalletUnlocked();
            memberCallChecked.set(true);
            return ACCOUNT;
        });
        willAnswer(invocation -> {
            assertNoTransactionAndWalletUnlocked();
            bankCallChecked.set(true);
            return success(invocation.getArgument(0));
        }).given(bankTransferClient).transfer(any());

        CashOutResult result = cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(CashOutStatus.COMPLETED);
        assertThat(memberCallChecked).isTrue();
        assertThat(bankCallChecked).isTrue();
    }

    // 다른 연결에서 회원 지갑을 nowait로 잠가 본다. 누가 잡고 있으면 바로 예외
    private void assertNoTransactionAndWalletUnlocked() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        jdbcTemplate.queryForList("select id from wallet where member_id = ? for update nowait", MEMBER_ID);
    }

    // ===== 도움 =====

    private void saveWallet(String balance) {
        walletRepository.save(Wallet.forMember(MEMBER_ID));
        jdbcTemplate.update("update wallet set balance = ? where member_id = ?", new BigDecimal(balance), MEMBER_ID);
    }

    private BigDecimal balance() {
        return walletRepository.findByMemberId(MEMBER_ID).orElseThrow().getBalance();
    }

    private void assertNothingChanged(String expectedBalance) {
        assertThat(balance()).isEqualByComparingTo(expectedBalance);
        assertThat(cashOutRepository.count()).isZero();
        assertThat(walletTransactionRepository.count()).isZero();
        verify(bankTransferClient, never()).transfer(any());
    }

    private static void assertRejected(Runnable action, PaymentErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(errorCode);
    }

    private static BankTransferResult success(BankTransferRequest request) {
        return new BankTransferResult(request.idempotencyKey(), "TEST-" + request.idempotencyKey());
    }

    private static <T> List<T> runConcurrently(int threads, java.util.function.IntFunction<Callable<T>> task)
            throws Exception {
        List<T> results = new ArrayList<>();
        for (Object outcome : runConcurrentlyCatching(threads, task)) {
            if (outcome instanceof Throwable t) {
                throw new AssertionError("동시 요청 중 예외", t);
            }
            @SuppressWarnings("unchecked") T value = (T) outcome;
            results.add(value);
        }
        return results;
    }

    private static List<Object> runConcurrentlyCatching(int threads, java.util.function.IntFunction<? extends Callable<?>> task)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<?> callable = task.apply(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return callable.call();
                    } catch (BusinessException e) {
                        return e;
                    }
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }
}
