package com.justcommit.backend.settlement.application.withdrawal;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.settlement.domain.Settlement;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import com.justcommit.backend.settlement.presentation.dto.WithdrawalBlockingReason;
import com.justcommit.backend.settlement.presentation.dto.WithdrawalCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

// 탈퇴 확인(#441)을 Flyway 스키마와 실제 PostgreSQL에서 확인한다 (판정 기준, 판매자 구분, 정산 없는 회원)
// 정산 지급 스케줄러와 Kafka 리스너는 끈다 (백그라운드 실행이 테스트 데이터의 상태를 바꾸지 않게)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WithdrawalCheckServiceIntegrationTest {

    private static final long SELLER_ID = 441L;
    private static final long OTHER_SELLER_ID = 442L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 12, 0);

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("jwt.public-key", WithdrawalCheckServiceIntegrationTest::newPublicKey);
        registry.add("internal-api.key", () -> "test-internal-key");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("custom.settlement.payout.enabled", () -> "false");
        registry.add("outbox.scheduler.enabled", () -> "false");
    }

    @Autowired WithdrawalCheckService withdrawalCheckService;
    @Autowired SettlementRepository settlementRepository;

    private long sellerOrderSeq = 0;

    @BeforeEach
    void cleanUp() {
        settlementRepository.deleteAll();
    }

    @Test
    @DisplayName("정산 기록이 없는 회원은 차단하지 않는다")
    void noSettlement() {
        WithdrawalCheckResponse result = withdrawalCheckService.check(SELLER_ID);

        assertNotBlocked(result);
    }

    @Test
    @DisplayName("PENDING 정산이 있으면 INCOMPLETE_SETTLEMENT로 막는다")
    void pendingBlocks() {
        pending(SELLER_ID);

        WithdrawalCheckResponse result = withdrawalCheckService.check(SELLER_ID);

        assertBlocked(result, 1);
    }

    @Test
    @DisplayName("FAILED 정산이 있으면 INCOMPLETE_SETTLEMENT로 막는다")
    void failedBlocks() {
        failed(SELLER_ID);

        WithdrawalCheckResponse result = withdrawalCheckService.check(SELLER_ID);

        assertBlocked(result, 1);
    }

    @Test
    @DisplayName("COMPLETED 정산은 세지 않는다")
    void completedIsNotCounted() {
        completed(SELLER_ID);

        WithdrawalCheckResponse result = withdrawalCheckService.check(SELLER_ID);

        assertNotBlocked(result);
    }

    @Test
    @DisplayName("다른 판매자의 정산은 세지 않는다")
    void otherSellerIsNotCounted() {
        pending(OTHER_SELLER_ID);
        failed(OTHER_SELLER_ID);

        WithdrawalCheckResponse result = withdrawalCheckService.check(SELLER_ID);

        assertNotBlocked(result);
    }

    @Test
    @DisplayName("PENDING과 FAILED만 더해 세고 COMPLETED는 빼고 센다")
    void countsOnlyIncomplete() {
        pending(SELLER_ID);
        failed(SELLER_ID);
        completed(SELLER_ID);

        WithdrawalCheckResponse result = withdrawalCheckService.check(SELLER_ID);

        assertBlocked(result, 2);
    }

    @Test
    @DisplayName("정산금이 음수인 PENDING 정산도 센다 (회수 전이라 막음)")
    void negativeSettlementBlocks() {
        // 판매액 10,000원, 판매자 부담금 15,000원 → 음수 정산
        settlementRepository.save(Settlement.createSettlement(nextSellerOrderId(), SELLER_ID,
                new BigDecimal("10000"), new BigDecimal("3000"), new BigDecimal("15000"), NOW, "음수 정산"));

        WithdrawalCheckResponse result = withdrawalCheckService.check(SELLER_ID);

        assertBlocked(result, 1);
    }

    private Settlement pending(long sellerId) {
        return settlementRepository.save(Settlement.createSettlement(nextSellerOrderId(), sellerId,
                new BigDecimal("30000"), new BigDecimal("3000"), BigDecimal.ZERO, NOW, "몬스테라"));
    }

    private void failed(long sellerId) {
        Settlement settlement = pending(sellerId);
        settlement.fail("지급 실패", NOW.plusMinutes(5));
        settlementRepository.save(settlement);
    }

    private void completed(long sellerId) {
        Settlement settlement = pending(sellerId);
        settlement.complete(NOW);
        settlementRepository.save(settlement);
    }

    private long nextSellerOrderId() {
        return ++sellerOrderSeq;
    }

    private static void assertNotBlocked(WithdrawalCheckResponse result) {
        assertThat(result.hasWithdrawalBlocker()).isFalse();
        assertThat(result.blockingReasons()).isEmpty();
        assertThat(result.details().incompleteSettlementCount()).isZero();
    }

    private static void assertBlocked(WithdrawalCheckResponse result, long expectedCount) {
        assertThat(result.hasWithdrawalBlocker()).isTrue();
        assertThat(result.blockingReasons()).containsExactly(WithdrawalBlockingReason.INCOMPLETE_SETTLEMENT);
        assertThat(result.details().incompleteSettlementCount()).isEqualTo(expectedCount);
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
