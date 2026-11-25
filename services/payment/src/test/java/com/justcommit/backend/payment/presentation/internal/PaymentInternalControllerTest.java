package com.justcommit.backend.payment.presentation.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.internal.InternalKeyFilter;
import com.justcommit.backend.payment.application.PaymentResult;
import com.justcommit.backend.payment.application.PaymentService;
import com.justcommit.backend.payment.application.settlement.SettlementLedgerQueryService;
import com.justcommit.backend.payment.application.settlement.SettlementLedgerRow;
import com.justcommit.backend.payment.application.withdrawal.WithdrawalCheckService;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.WalletType;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.presentation.internal.dto.WithdrawalCheckResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PaymentInternalControllerTest {

    private static final String INTERNAL_KEY = "test-internal-key";
    private static final String WITHDRAWAL_CHECK_URL = "/internal/v1/payments/members/{memberId}/withdrawalCheck";
    private static final String PAYMENT_BODY = """
            {"orderId": 7, "buyerId": 3, "amount": 15000}
            """;
    private static final String PAYOUT_BODY = """
            {"settlementId": 9, "sellerId": 4, "payoutAmount": 9500, "fee": 500}
            """;
    private static final String FULL_PAYOUT_BODY = """
            {"settlementId": 9, "sellerId": 4, "sellerOrderId": 12, "payoutAmount": -2500, "fee": 500,
             "shippingFee": 3000, "sellerBurdenAmount": 12000}
            """;

    @Mock
    private PaymentService paymentService;

    @Mock
    private WithdrawalCheckService withdrawalCheckService;

    @Mock
    private SettlementLedgerQueryService settlementLedgerQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 실제 앱처럼 내부 키 필터를 앞에 둔다 (/internal/** 요청은 X-Internal-Key가 맞아야 통과)
        mockMvc = MockMvcBuilders.standaloneSetup(new PaymentInternalController(
                        paymentService, withdrawalCheckService, settlementLedgerQueryService))
                .addFilters(new InternalKeyFilter(INTERNAL_KEY))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("결제는 market 클라이언트가 읽는 {status, balanceAfter} 형식으로 응답한다")
    void pay() throws Exception {
        given(paymentService.pay(7L, 3L, new BigDecimal("15000")))
                .willReturn(PaymentResult.paid(new BigDecimal("5000")));

        mockMvc.perform(internalPost("/internal/v1/payments", "7", PAYMENT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.balanceAfter").value(5000));
    }

    @Test
    @DisplayName("잔액 부족은 예외가 아니라 200 응답의 결과 상태로 보낸다")
    void payInsufficientBalance() throws Exception {
        given(paymentService.pay(7L, 3L, new BigDecimal("15000")))
                .willReturn(PaymentResult.insufficientBalance(new BigDecimal("1000")));

        mockMvc.perform(internalPost("/internal/v1/payments", "7", PAYMENT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    @DisplayName("상품 금액 환불은 경로의 orderItemId로 환불한다")
    void refundOrderItem() throws Exception {
        given(paymentService.refundCancelledItem(5L, 3L, new BigDecimal("15000")))
                .willReturn(PaymentResult.refunded(new BigDecimal("20000")));

        mockMvc.perform(internalPost("/internal/v1/refunds/orderItems/5", "5", PAYMENT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDED"))
                .andExpect(jsonPath("$.data.balanceAfter").value(20000));
    }

    @Test
    @DisplayName("배송비 환불은 경로의 sellerOrderId로 환불한다")
    void refundShippingFee() throws Exception {
        given(paymentService.refundShippingFee(8L, 3L, new BigDecimal("15000")))
                .willReturn(PaymentResult.alreadyRefunded(new BigDecimal("20000")));

        mockMvc.perform(internalPost("/internal/v1/refunds/shippingFees/8", "8", PAYMENT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ALREADY_REFUNDED"));
    }

    @Test
    @DisplayName("정산 지급은 본문 값 그대로(판매자 주문 · 배송비 · 부담금 포함) 지급하고 데이터 없이 성공 응답한다")
    void payoutSettlement() throws Exception {
        mockMvc.perform(internalPost("/internal/v1/settlementPayouts", "9", FULL_PAYOUT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(paymentService).distributeSettlement(9L, 4L, 12L, new BigDecimal("-2500"), new BigDecimal("500"),
                new BigDecimal("3000"), new BigDecimal("12000"));
    }

    @Test
    @DisplayName("배송비 · 부담금이 없는 요청(#433 배포 전)은 0으로 넘긴다")
    void payoutSettlementWithoutShippingFeeAndBurden() throws Exception {
        mockMvc.perform(internalPost("/internal/v1/settlementPayouts", "9", PAYOUT_BODY))
                .andExpect(status().isOk());

        verify(paymentService).distributeSettlement(9L, 4L, null, new BigDecimal("9500"), new BigDecimal("500"),
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("배송비 · 부담금이 null이면 0으로 넘긴다")
    void payoutSettlementWithNullShippingFeeAndBurden() throws Exception {
        mockMvc.perform(internalPost("/internal/v1/settlementPayouts", "9", """
                        {"settlementId": 9, "sellerId": 4, "sellerOrderId": 12, "payoutAmount": 9500, "fee": 500,
                         "shippingFee": null, "sellerBurdenAmount": null}
                        """))
                .andExpect(status().isOk());

        verify(paymentService).distributeSettlement(9L, 4L, 12L, new BigDecimal("9500"), new BigDecimal("500"),
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("정산 지급 검증 실패는 400 INVALID_AMOUNT로 나간다")
    void payoutValidationFailureIs400() throws Exception {
        willThrow(new BusinessException(PaymentErrorCode.INVALID_AMOUNT))
                .given(paymentService).distributeSettlement(anyLong(), anyLong(), any(), any(), any(), any(), any());

        mockMvc.perform(internalPost("/internal/v1/settlementPayouts", "9", FULL_PAYOUT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
    }

    @Test
    @DisplayName("환불 확정 실패는 공통 처리기를 거쳐 4xx와 에러 코드로 나간다")
    void refundFailureIs4xx() throws Exception {
        willThrow(new BusinessException(PaymentErrorCode.REFUND_INFO_MISMATCH))
                .given(paymentService).refundCancelledItem(anyLong(), anyLong(), any());

        mockMvc.perform(internalPost("/internal/v1/refunds/orderItems/5", "5", PAYMENT_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_INFO_MISMATCH"));
    }

    @Test
    @DisplayName("Idempotency-Key가 업무 ID와 다르면 400 BAD_REQUEST_DATA")
    void rejectsKeyMismatch() throws Exception {
        mockMvc.perform(internalPost("/internal/v1/payments", "8", PAYMENT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST_DATA"));
        mockMvc.perform(internalPost("/internal/v1/refunds/orderItems/5", "6", PAYMENT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST_DATA"));
        mockMvc.perform(internalPost("/internal/v1/refunds/shippingFees/8", "9", PAYMENT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST_DATA"));
        mockMvc.perform(internalPost("/internal/v1/settlementPayouts", "10", PAYOUT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST_DATA"));

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("Idempotency-Key가 없으면 400 MISSING_HEADER")
    void rejectsMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/internal/v1/payments")
                        .header(InternalKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYMENT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("X-Internal-Key가 없거나 틀리면 401")
    void rejectsMissingOrWrongInternalKey() throws Exception {
        mockMvc.perform(post("/internal/v1/payments")
                        .header("Idempotency-Key", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYMENT_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/v1/settlementPayouts")
                        .header(InternalKeyFilter.HEADER, "wrong-key")
                        .header("Idempotency-Key", "9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYOUT_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(paymentService);
    }

    // ===== 탈퇴 확인 (#329) =====

    @Test
    @DisplayName("탈퇴 확인은 공통 규격(hasWithdrawalBlocker · blockingReasons · details)으로 응답한다")
    void withdrawalCheckBlocked() throws Exception {
        given(withdrawalCheckService.check(7L)).willReturn(
                WithdrawalCheckResponse.of(new BigDecimal("15000"), true, new BigDecimal("2500")));

        mockMvc.perform(get(WITHDRAWAL_CHECK_URL, 7L).header(InternalKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.hasWithdrawalBlocker").value(true))
                .andExpect(jsonPath("$.data.blockingReasons.length()").value(3))
                .andExpect(jsonPath("$.data.blockingReasons[0]").value("WALLET_BALANCE_REMAINING"))
                .andExpect(jsonPath("$.data.blockingReasons[1]").value("TRANSACTION_IN_PROGRESS"))
                .andExpect(jsonPath("$.data.blockingReasons[2]").value("SELLER_DEBT_REMAINING"))
                .andExpect(jsonPath("$.data.details.walletBalance").value(15000))
                .andExpect(jsonPath("$.data.details.hasPendingTransactions").value(true))
                .andExpect(jsonPath("$.data.details.outstandingDebt").value(2500));
    }

    @Test
    @DisplayName("차단 사유가 없으면 false · [] 로 응답한다 (null이 아님)")
    void withdrawalCheckNotBlocked() throws Exception {
        given(withdrawalCheckService.check(8L)).willReturn(WithdrawalCheckResponse.noWallet());

        mockMvc.perform(get(WITHDRAWAL_CHECK_URL, 8L).header(InternalKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasWithdrawalBlocker").value(false))
                .andExpect(jsonPath("$.data.blockingReasons").isArray())
                .andExpect(jsonPath("$.data.blockingReasons").isEmpty())
                .andExpect(jsonPath("$.data.details.walletBalance").value(0))
                .andExpect(jsonPath("$.data.details.hasPendingTransactions").value(false))
                .andExpect(jsonPath("$.data.details.outstandingDebt").value(0));
    }

    @Test
    @DisplayName("탈퇴 확인 중 DB 오류는 5xx로 보낸다 (member가 Fail-closed로 막음)")
    void withdrawalCheckDatabaseErrorIs5xx() throws Exception {
        given(withdrawalCheckService.check(9L)).willThrow(new DataAccessResourceFailureException("db down"));

        mockMvc.perform(get(WITHDRAWAL_CHECK_URL, 9L).header(InternalKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().is5xxServerError());
    }

    @Test
    @DisplayName("탈퇴 확인에 X-Internal-Key가 없으면 401 INVALID_INTERNAL_KEY")
    void withdrawalCheckRejectsMissingInternalKey() throws Exception {
        mockMvc.perform(get(WITHDRAWAL_CHECK_URL, 7L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INTERNAL_KEY"));

        verifyNoInteractions(withdrawalCheckService);
    }

    private static MockHttpServletRequestBuilder internalPost(String url, String idempotencyKey, String body) {
        return post(url)
                .header(InternalKeyFilter.HEADER, INTERNAL_KEY)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    // ===== 정산 원장 조회 =====

    private static final String LEDGER_URL = "/internal/v1/settlementPayouts/ledgers";

    @Test
    @DisplayName("정산 원장은 settlementId별 행 목록으로 응답하고, 행이 없는 정산은 빈 목록이다")
    void findSettlementLedgers() throws Exception {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 11, 25, 10, 0);
        Map<Long, List<SettlementLedgerRow>> ledgers = new LinkedHashMap<>();
        ledgers.put(9L, List.of(
                new SettlementLedgerRow(9L, 1L, WalletType.HOLDING, null, RefType.SETTLEMENT,
                        TransactionStatus.OUT, new BigDecimal("10000"), occurredAt),
                new SettlementLedgerRow(9L, 5L, WalletType.MEMBER, 4L, RefType.SETTLEMENT,
                        TransactionStatus.IN, new BigDecimal("9500"), occurredAt)));
        ledgers.put(10L, List.of());
        given(settlementLedgerQueryService.findLedgers(List.of(9L, 10L))).willReturn(ledgers);

        mockMvc.perform(ledgerPost("""
                        {"settlementIds": [9, 10]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ledgers.length()").value(2))
                .andExpect(jsonPath("$.data.ledgers['9'].length()").value(2))
                .andExpect(jsonPath("$.data.ledgers['9'][0].walletId").value(1))
                .andExpect(jsonPath("$.data.ledgers['9'][0].walletType").value("HOLDING"))
                .andExpect(jsonPath("$.data.ledgers['9'][0].memberId").doesNotExist())
                .andExpect(jsonPath("$.data.ledgers['9'][0].refType").value("SETTLEMENT"))
                .andExpect(jsonPath("$.data.ledgers['9'][0].status").value("OUT"))
                .andExpect(jsonPath("$.data.ledgers['9'][0].amount").value(10000))
                .andExpect(jsonPath("$.data.ledgers['9'][0].occurredAt").exists())
                .andExpect(jsonPath("$.data.ledgers['9'][1].memberId").value(4))
                .andExpect(jsonPath("$.data.ledgers['10']").isArray())
                .andExpect(jsonPath("$.data.ledgers['10']").isEmpty());
    }

    @Test
    @DisplayName("정산 원장 조회는 100개까지 받는다")
    void findSettlementLedgersAcceptsHundredIds() throws Exception {
        given(settlementLedgerQueryService.findLedgers(any())).willReturn(Map.of());

        mockMvc.perform(ledgerPost(idsBody(100)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("정산 원장 조회의 settlementIds가 0개 · 101개 · null · 원소 null이면 400 INVALID_FIELD_ERROR")
    void findSettlementLedgersRejectsInvalidIds() throws Exception {
        for (String body : List.of(
                "{\"settlementIds\": []}",
                idsBody(101),
                "{\"settlementIds\": null}",
                "{}",
                "{\"settlementIds\": [9, null]}")) {
            mockMvc.perform(ledgerPost(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
        }

        verifyNoInteractions(settlementLedgerQueryService);
    }

    @Test
    @DisplayName("정산 원장 조회에 X-Internal-Key가 없거나 틀리면 401 INVALID_INTERNAL_KEY")
    void findSettlementLedgersRejectsMissingOrWrongInternalKey() throws Exception {
        mockMvc.perform(post(LEDGER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settlementIds\": [9]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INTERNAL_KEY"));
        mockMvc.perform(post(LEDGER_URL)
                        .header(InternalKeyFilter.HEADER, "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settlementIds\": [9]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INTERNAL_KEY"));

        verifyNoInteractions(settlementLedgerQueryService);
    }

    private MockHttpServletRequestBuilder ledgerPost(String body) {
        return post(LEDGER_URL)
                .header(InternalKeyFilter.HEADER, INTERNAL_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String idsBody(int count) {
        return LongStream.rangeClosed(1, count).mapToObj(String::valueOf)
                .collect(Collectors.joining(", ", "{\"settlementIds\": [", "]}"));
    }
}
