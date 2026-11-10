package com.justcommit.backend.payment.presentation.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.internal.InternalKeyFilter;
import com.justcommit.backend.payment.application.PaymentResult;
import com.justcommit.backend.payment.application.PaymentService;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PaymentInternalControllerTest {

    private static final String INTERNAL_KEY = "test-internal-key";
    private static final String PAYMENT_BODY = """
            {"orderId": 7, "buyerId": 3, "amount": 15000}
            """;
    private static final String PAYOUT_BODY = """
            {"settlementId": 9, "sellerId": 4, "payoutAmount": 9500, "fee": 500}
            """;

    @Mock
    private PaymentService paymentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 실제 앱처럼 내부 키 필터를 앞에 둔다 (/internal/** 요청은 X-Internal-Key가 맞아야 통과)
        mockMvc = MockMvcBuilders.standaloneSetup(new PaymentInternalController(paymentService))
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
    @DisplayName("정산 지급은 본문 값 그대로 지급하고 데이터 없이 성공 응답한다")
    void payoutSettlement() throws Exception {
        mockMvc.perform(internalPost("/internal/v1/settlementPayouts", "9", PAYOUT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(paymentService).distributeSettlement(9L, 4L, new BigDecimal("9500"), new BigDecimal("500"));
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

    private static MockHttpServletRequestBuilder internalPost(String url, String idempotencyKey, String body) {
        return post(url)
                .header(InternalKeyFilter.HEADER, INTERNAL_KEY)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
