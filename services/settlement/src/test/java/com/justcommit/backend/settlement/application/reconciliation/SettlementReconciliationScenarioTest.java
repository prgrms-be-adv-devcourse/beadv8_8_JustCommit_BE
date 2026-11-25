package com.justcommit.backend.settlement.application.reconciliation;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.settlement.domain.Settlement;
import com.justcommit.backend.settlement.domain.SettlementStatus;
import com.justcommit.backend.settlement.domain.reconciliation.ReconciliationRunResult;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

// 정산 대사 시나리오: payment 자리에 WireMock을 두고 실제 HTTP 클라이언트(internal-payment 그룹), 실제 PostgreSQL로
// 대사 한 회차를 돌려 불일치 기록, 건너뛴 배치, 실행 결과, ShedLock을 확인한다
// 대사 스케줄러 빈은 켜되 cron을 "-"로 꺼서 테스트가 직접 부를 때만 돈다
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SettlementReconciliationScenarioTest {

    private static final String LEDGERS = "/internal/v1/settlementPayouts/ledgers";
    private static final long SELLER_ID = 7L;
    private static final long OTHER_SELLER_ID = 8L;
    private static final long SELLER_WALLET_ID = 1L;
    private static final long OTHER_SELLER_WALLET_ID = 2L;
    private static final long HOLDING_WALLET_ID = 100L;
    private static final long REVENUE_WALLET_ID = 200L;

    @RegisterExtension
    static WireMockExtension payment = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("jwt.public-key", SettlementReconciliationScenarioTest::newPublicKey);
        registry.add("internal-api.key", () -> "test-internal-key");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("custom.settlement.payout.enabled", () -> "false");
        registry.add("custom.settlement.reconciliation.enabled", () -> "true");
        registry.add("custom.settlement.reconciliation.cron", () -> "-");
        registry.add("outbox.scheduler.enabled", () -> "false");
        registry.add("spring.http.serviceclient.internal-payment.base-url", payment::baseUrl);
        // 타임아웃 시나리오를 빨리 끝내려고 읽기 타임아웃만 줄인다 (운영 3초)
        registry.add("spring.http.serviceclient.internal-payment.read-timeout", () -> "1s");
    }

    @Autowired SettlementReconciliationService reconciliationService;
    @Autowired SettlementReconciliationScheduler reconciliationScheduler;
    @Autowired SettlementRepository settlementRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private long sellerOrderSeq = 0;
    private final LocalDateTime paidAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusHours(1);

    @BeforeEach
    void cleanUp() {
        settlementRepository.deleteAll();
        jdbcTemplate.execute(
                "truncate table settlement_reconciliation_mismatch, settlement_reconciliation_run, shedlock");
    }

    @Test
    @DisplayName("정상: 양수, 0원, 음수(전액 회수, 일부 회수 + 미수금, 전부 반품 미회수) 정산은 불일치가 없다")
    void normalSettlementsHaveNoMismatch() {
        Settlement positive = completed(settlement("30000", "0"));
        Settlement zero = completed(settlement("30000", "28500"));
        Settlement fullyCollected = completed(settlement("10000", "12000"));
        Settlement partiallyCollected = completed(settlement("10000", "12000"));
        Settlement returnedUncollected = completed(returned("9000"));
        stubLedgers(Map.of(
                positive.getId(), positiveLedger(positive.getId(), "1500"),
                zero.getId(), rows(holding(zero.getId(), "30000"), fee(zero.getId(), "1500"),
                        sellerIn(zero.getId(), "0"), burden(zero.getId(), "28500")),
                // 정산금 -2500을 판매자 잔액에서 모두 회수 → 부담금 12000 그대로 REVENUE
                fullyCollected.getId(), rows(holding(fullyCollected.getId(), "10000"), fee(fullyCollected.getId(), "500"),
                        sellerIn(fullyCollected.getId(), "0"), collection(fullyCollected.getId(), "2500"),
                        burden(fullyCollected.getId(), "12000")),
                // 1000만 회수하고 1500은 미수금 → 부담금 IN = 12000 - 2500 + 1000
                partiallyCollected.getId(), rows(holding(partiallyCollected.getId(), "10000"),
                        fee(partiallyCollected.getId(), "500"), sellerIn(partiallyCollected.getId(), "0"),
                        collection(partiallyCollected.getId(), "1000"), burden(partiallyCollected.getId(), "10500")),
                // 판매액 0, 잔액 0이라 전액 미수금 → 판매자 0원 행만
                returnedUncollected.getId(), rows(sellerIn(returnedUncollected.getId(), "0"))));

        assertThat(reconciliationService.reconcile()).isEqualTo(ReconciliationRunResult.SUCCEEDED);

        assertThat(mismatches()).isEmpty();
        assertThat(lastRun()).containsEntry("target_count", 5).containsEntry("mismatch_count", 0);
    }

    @Test
    @DisplayName("COMPLETED인데 payment 원장이 없으면 MISSING_LEDGER")
    void missingLedger() {
        Settlement settlement = completed(settlement("30000", "0"));
        stubLedgers(Map.of(settlement.getId(), rows()));

        reconciliationService.reconcile();

        assertThat(mismatches()).containsExactly(settlement.getId() + " MISSING_LEDGER");
    }

    @Test
    @DisplayName("FAILED인데 원장이 있으면 지급 시각에서 10분이 지난 것만 UNREFLECTED_STATUS (10분 전이면 기록 안 함)")
    void unreflectedStatus() {
        Settlement overGrace = failed(settlement("30000", "0"));
        Settlement withinGrace = failed(settlement("30000", "0"));
        LocalDateTime now = LocalDateTime.now();
        stubLedgers(Map.of(
                overGrace.getId(), positiveLedger(overGrace.getId(), "1500", now.minusMinutes(11)),
                withinGrace.getId(), positiveLedger(withinGrace.getId(), "1500", now.minusMinutes(9))));

        reconciliationService.reconcile();

        assertThat(mismatches()).containsExactly(overGrace.getId() + " UNREFLECTED_STATUS");
        assertThat(statusOf(overGrace)).isEqualTo("FAILED"); // 대사는 정산 상태를 바꾸지 않는다
    }

    @Test
    @DisplayName("수수료, 판매자 부담금, 회수액이 기대 원장과 다르면 AMOUNT_MISMATCH")
    void amountMismatch() {
        Settlement wrongFee = completed(settlement("30000", "0"));
        Settlement wrongBurden = completed(settlement("30000", "28500"));
        Settlement wrongCollection = completed(settlement("10000", "12000"));
        stubLedgers(Map.of(
                wrongFee.getId(), positiveLedger(wrongFee.getId(), "1400"),
                wrongBurden.getId(), rows(holding(wrongBurden.getId(), "30000"), fee(wrongBurden.getId(), "1500"),
                        sellerIn(wrongBurden.getId(), "0"), burden(wrongBurden.getId(), "28000")),
                // 1000 회수라면 부담금 IN은 10500이어야 함
                wrongCollection.getId(), rows(holding(wrongCollection.getId(), "10000"), fee(wrongCollection.getId(), "500"),
                        sellerIn(wrongCollection.getId(), "0"), collection(wrongCollection.getId(), "1000"),
                        burden(wrongCollection.getId(), "12000"))));

        reconciliationService.reconcile();

        assertThat(mismatches()).containsExactlyInAnyOrder(
                wrongFee.getId() + " AMOUNT_MISMATCH",
                wrongBurden.getId() + " AMOUNT_MISMATCH",
                wrongCollection.getId() + " AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("다른 판매자 지갑에 SETTLEMENT 행이 있으면 UNEXPECTED_WALLET (기록에 memberId 없음)")
    void unexpectedWallet() {
        Settlement settlement = completed(settlement("30000", "0"));
        long id = settlement.getId();
        stubLedgers(Map.of(id, rows(holding(id, "30000"), fee(id, "1500"), sellerIn(id, "28500"),
                row(OTHER_SELLER_WALLET_ID, "MEMBER", OTHER_SELLER_ID, "SETTLEMENT", "IN", "28500", id, paidAt))));

        reconciliationService.reconcile();

        assertThat(mismatches()).containsExactly(id + " UNEXPECTED_WALLET");
        assertThat(jdbcTemplate.queryForObject("select actual from settlement_reconciliation_mismatch", String.class))
                .isEqualTo("walletId=" + OTHER_SELLER_WALLET_ID + " MEMBER SETTLEMENT IN 28500");
    }

    @Test
    @DisplayName("응답에서 settlementId가 빠지면 그 배치는 비교하지 않고 건너뛴다 (불일치 기록 없음, 배치가 하나뿐이라 FAILED)")
    void missingKeySkipsBatch() {
        Settlement answered = completed(settlement("30000", "0"));
        Settlement omitted = completed(settlement("30000", "0"));
        // answered는 원장이 없어 비교했다면 MISSING_LEDGER가 됐을 것
        stubLedgers(Map.of(answered.getId(), rows()));

        assertThat(reconciliationService.reconcile()).isEqualTo(ReconciliationRunResult.FAILED);

        assertThat(mismatches()).isEmpty();
        assertThat(lastRun()).containsEntry("failed_batch_count", 1);
        assertThat(omitted.getId()).isNotNull();
    }

    @Test
    @DisplayName("같은 불일치를 두 회차 연속 찾아도 한 건만 기록한다")
    void sameMismatchRecordedOnce() {
        Settlement settlement = completed(settlement("30000", "0"));
        stubLedgers(Map.of(settlement.getId(), rows()));

        reconciliationService.reconcile();
        reconciliationService.reconcile();

        assertThat(mismatches()).containsExactly(settlement.getId() + " MISSING_LEDGER");
        assertThat(jdbcTemplate.queryForObject("select count(*) from settlement_reconciliation_run", Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("대상이 100개를 넘으면 100개씩 나눠 모두 비교한다")
    void splitsIntoBatchesOf100() {
        List<Settlement> settlements = completedSettlements(150);
        stubBatch(settlements.subList(0, 100), settlement -> positiveLedger(settlement.getId(), "1500"));
        stubBatch(settlements.subList(100, 150), settlement -> positiveLedger(settlement.getId(), "1500"));

        assertThat(reconciliationService.reconcile()).isEqualTo(ReconciliationRunResult.SUCCEEDED);

        payment.verify(2, postRequestedFor(urlPathEqualTo(LEDGERS)));
        assertThat(mismatches()).isEmpty();
        assertThat(lastRun()).containsEntry("target_count", 150).containsEntry("failed_batch_count", 0);
    }

    @ParameterizedTest(name = "첫 배치 응답: {0}")
    @ValueSource(strings = {"500", "timeout"})
    @DisplayName("payment 5xx나 타임아웃이면 그 배치만 건너뛰고(불일치 기록 없음) 다른 배치는 정상 비교한다")
    void failedBatchSkippedOthersProcessed(String failure) {
        List<Settlement> settlements = completedSettlements(101);
        Settlement inFailedBatch = settlements.getFirst();
        Settlement inSecondBatch = settlements.getLast();
        var failedResponse = "timeout".equals(failure)
                ? okJson(ledgersJson(Map.of())).withFixedDelay(1_500)
                : aResponse().withStatus(500);
        payment.stubFor(post(urlPathEqualTo(LEDGERS))
                .withRequestBody(matchingJsonPath("$.settlementIds[0]", equalTo(String.valueOf(inFailedBatch.getId()))))
                .willReturn(failedResponse));
        // 두 번째 배치의 정산은 원장이 없어 MISSING_LEDGER가 나와야 한다 (비교했다는 증거)
        stubBatch(List.of(inSecondBatch), settlement -> rows());

        ReconciliationRunResult result = reconciliationService.reconcile();

        assertThat(result).isEqualTo(ReconciliationRunResult.PARTIAL);
        assertThat(mismatches()).containsExactly(inSecondBatch.getId() + " MISSING_LEDGER");
        assertThat(lastRun()).containsEntry("target_count", 101).containsEntry("failed_batch_count", 1);
    }

    // 같은 JVM의 두 스레드로 두 인스턴스를 흉내 낸다. 잠금은 DB(shedlock 행)에 잡히므로 인스턴스가 달라도 같다
    @Test
    @DisplayName("두 인스턴스가 동시에 대사를 시작해도 ShedLock으로 한 번만 돈다")
    void runsOnceWithShedLock() throws Exception {
        Settlement settlement = completed(settlement("30000", "0"));
        payment.stubFor(post(urlPathEqualTo(LEDGERS))
                .willReturn(okJson(ledgersJson(Map.of(settlement.getId(), positiveLedger(settlement.getId(), "1500"))))
                        .withFixedDelay(500)));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    reconciliationScheduler.reconcile();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        payment.verify(1, postRequestedFor(urlPathEqualTo(LEDGERS)));
        assertThat(jdbcTemplate.queryForObject("select count(*) from settlement_reconciliation_run", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from shedlock where name = 'settlementReconcile'",
                Integer.class)).isEqualTo(1);
    }

    // ---- 정산 준비

    // 판매액, 판매자 부담금으로 PENDING 정산 (수수료 5%)
    private Settlement settlement(String saleAmount, String sellerBurdenAmount) {
        return settlementRepository.save(Settlement.createSettlement(++sellerOrderSeq, SELLER_ID,
                new BigDecimal(saleAmount), new BigDecimal("3000"), new BigDecimal(sellerBurdenAmount),
                LocalDateTime.now(), "몬스테라"));
    }

    private Settlement returned(String sellerBurdenAmount) {
        return settlementRepository.save(Settlement.createReturnedSettlement(++sellerOrderSeq, SELLER_ID,
                new BigDecimal(sellerBurdenAmount), LocalDateTime.now(), "몬스테라 외 1건"));
    }

    private List<Settlement> completedSettlements(int count) {
        List<Settlement> settlements = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            settlements.add(Settlement.createSettlement(++sellerOrderSeq, SELLER_ID, new BigDecimal("30000"),
                    new BigDecimal("3000"), BigDecimal.ZERO, LocalDateTime.now(), "몬스테라"));
        }
        List<Settlement> saved = settlementRepository.saveAll(settlements);
        jdbcTemplate.update("update settlement set status = 'COMPLETED', completed_at = ?", Timestamp.valueOf(paidAt));
        return saved;
    }

    private Settlement completed(Settlement settlement) {
        jdbcTemplate.update("update settlement set status = 'COMPLETED', completed_at = ? where id = ?",
                Timestamp.valueOf(paidAt), settlement.getId());
        return settlement;
    }

    private Settlement failed(Settlement settlement) {
        jdbcTemplate.update("update settlement set status = 'FAILED', attempt_count = 1 where id = ?",
                settlement.getId());
        return settlement;
    }

    private String statusOf(Settlement settlement) {
        return jdbcTemplate.queryForObject("select status from settlement where id = ?", String.class,
                settlement.getId());
    }

    // ---- 결과 확인

    private List<String> mismatches() {
        return jdbcTemplate.queryForList(
                "select settlement_id || ' ' || mismatch_type from settlement_reconciliation_mismatch order by id",
                String.class);
    }

    private Map<String, Object> lastRun() {
        return jdbcTemplate.queryForMap("select * from settlement_reconciliation_run order by id desc limit 1");
    }

    // ---- payment 응답 (ApiResponse 봉투 + settlementId별 원장)

    private void stubLedgers(Map<Long, String> ledgers) {
        payment.stubFor(post(urlPathEqualTo(LEDGERS)).willReturn(okJson(ledgersJson(ledgers))));
    }

    // 배치의 첫 settlementId로 요청을 구분해 그 배치의 원장만 돌려준다
    private void stubBatch(List<Settlement> batch, java.util.function.Function<Settlement, String> ledger) {
        Map<Long, String> ledgers = batch.stream().collect(Collectors.toMap(Settlement::getId, ledger,
                (a, b) -> a, LinkedHashMap::new));
        payment.stubFor(post(urlPathEqualTo(LEDGERS))
                .withRequestBody(matchingJsonPath("$.settlementIds[0]", equalTo(String.valueOf(batch.getFirst().getId()))))
                .willReturn(okJson(ledgersJson(ledgers))));
    }

    private static String ledgersJson(Map<Long, String> ledgers) {
        String entries = ledgers.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
                .collect(Collectors.joining(","));
        return """
                {"status":200,"code":"SUCCESS","message":"정산 원장을 조회했습니다.","data":{"ledgers":{%s}}}
                """.formatted(entries);
    }

    private String positiveLedger(long id, String fee) {
        return positiveLedger(id, fee, paidAt);
    }

    // 판매액 30000, 정산금 28500 기준 (수수료만 바꿔 넣을 수 있음)
    private String positiveLedger(long id, String fee, LocalDateTime occurredAt) {
        return rows(row(HOLDING_WALLET_ID, "HOLDING", null, "SETTLEMENT", "OUT", "30000", id, occurredAt),
                row(REVENUE_WALLET_ID, "REVENUE", null, "SETTLEMENT_FEE", "IN", fee, id, occurredAt),
                row(SELLER_WALLET_ID, "MEMBER", SELLER_ID, "SETTLEMENT", "IN", "28500", id, occurredAt));
    }

    private String holding(long id, String amount) {
        return row(HOLDING_WALLET_ID, "HOLDING", null, "SETTLEMENT", "OUT", amount, id, paidAt);
    }

    private String fee(long id, String amount) {
        return row(REVENUE_WALLET_ID, "REVENUE", null, "SETTLEMENT_FEE", "IN", amount, id, paidAt);
    }

    private String burden(long id, String amount) {
        return row(REVENUE_WALLET_ID, "REVENUE", null, "SELLER_BURDEN", "IN", amount, id, paidAt);
    }

    private String sellerIn(long id, String amount) {
        return row(SELLER_WALLET_ID, "MEMBER", SELLER_ID, "SETTLEMENT", "IN", amount, id, paidAt);
    }

    private String collection(long id, String amount) {
        return row(SELLER_WALLET_ID, "MEMBER", SELLER_ID, "SETTLEMENT_COLLECTION", "OUT", amount, id, paidAt);
    }

    private static String rows(String... rows) {
        return "[" + String.join(",", rows) + "]";
    }

    // payment 응답의 원장 한 행. settlementId는 응답 키라 행에는 없다 (id는 가독성용 인자)
    private static String row(long walletId, String walletType, Long memberId, String refType, String status,
                              String amount, long settlementId, LocalDateTime occurredAt) {
        return """
                {"walletId":%d,"walletType":"%s","memberId":%s,"refType":"%s","status":"%s","amount":%s,"occurredAt":"%s"}
                """.formatted(walletId, walletType, memberId, refType, status, amount, occurredAt).strip();
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
