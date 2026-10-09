package com.justcommit.backend.payment.infrastructure.pg;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;

@Slf4j
@Component
public class TossPaymentClient {

    private static final String CONFIRM_PATH = "/v1/payments/confirm";
    private final RestClient restClient;

    public TossPaymentClient(
            @Value("${custom.payment.toss.secret-key}") String secretKey,
            @Value("${custom.payment.toss.base-url}") String baseUrl
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders(headers -> headers.setBasicAuth(secretKey, ""))
                .build();
        // log.info("toss key length={}", secretKey.length()); // 확인용
    }

    public TossConfirmResponse confirm(String paymentKey, String orderId, BigDecimal amount) {
        TossConfirmRequest request = new TossConfirmRequest(paymentKey, orderId, amount);

        try {
            return restClient.post()
                    .uri(CONFIRM_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(TossConfirmResponse.class);

        } catch (RestClientResponseException e) {
            // 토스가 4xx/5xx로 응답한 경우: 에러 바디의 code/message를 그대로 전달
            TossErrorResponse error = parseError(e);
            log.warn("토스 결제 승인 거절: orderId={}, status={}, code={}, message={}",
                    orderId, e.getStatusCode().value(), error.code(), error.message());
            throw new BusinessException(PaymentErrorCode.PG_CONFIRM_FAILED,
                    "[" + error.code() + "] " + error.message());

        } catch (ResourceAccessException e) {
            // 네트워크 오류, 타임아웃 등 토스에 도달하지 못한 경우
            log.error("토스 결제 승인 호출 실패: orderId={}", orderId, e);
            throw new BusinessException(PaymentErrorCode.PG_CONFIRM_FAILED, "결제 서버와 통신에 실패했습니다.");
        }
    }

    private TossErrorResponse parseError(RestClientResponseException e) {
        try {
            TossErrorResponse parsed = e.getResponseBodyAs(TossErrorResponse.class);
            if (parsed != null && parsed.code() != null) {
                return parsed;
            }
        } catch (Exception ignored) {
            // 바디가 JSON이 아니거나 형식이 다른 경우 아래 기본값 사용
        }
        return new TossErrorResponse("HTTP_" + e.getStatusCode().value(), "토스 결제 승인에 실패했습니다.");
    }

    private record TossConfirmRequest(String paymentKey, String orderId, BigDecimal amount) {
    }

    private record TossErrorResponse(String code, String message) {
    }
}
