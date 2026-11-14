package com.justcommit.backend.payment.infrastructure.pg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;

@Slf4j
@Component
public class TossPaymentClient {

    private static final String CONFIRM_PATH = "/v1/payments/confirm";

    // 4xx지만 실제로 승인됐을 수 있거나 처리 여부를 알 수 없는 응답
    private static final String ALREADY_PROCESSED_PAYMENT = "ALREADY_PROCESSED_PAYMENT";
    private static final Set<Integer> UNKNOWN_CLIENT_STATUSES = Set.of(408, 429);

    private final RestClient restClient;

    public TossPaymentClient(
            @Value("${custom.payment.toss.secret-key}") String secretKey,
            @Value("${custom.payment.toss.base-url}") String baseUrl,
            @Value("${custom.payment.toss.connect-timeout}") Duration connectTimeout,
            @Value("${custom.payment.toss.read-timeout}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeaders(headers -> headers.setBasicAuth(secretKey, ""))
                .build();
    }

    public TossConfirmResponse confirm(String paymentKey, String orderId, BigDecimal amount) {
        TossConfirmRequest request = new TossConfirmRequest(paymentKey, orderId, amount);

        try {
            return restClient.post()
                    .uri(CONFIRM_PATH)
                    .header("Idempotency-Key", orderId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(TossConfirmResponse.class);

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            TossErrorResponse error = parseError(e);

            if (e.getStatusCode().is5xxServerError()
                    || UNKNOWN_CLIENT_STATUSES.contains(status)
                    || ALREADY_PROCESSED_PAYMENT.equals(error.code())) {
                log.error("토스 결제 승인 결과 불명: orderId={}, status={}, code={}, message={}",
                        orderId, status, error.code(), error.message());
                throw new TossConfirmResultUnknownException(
                        "토스 승인 결과 불명: status=" + status + ", code=" + error.code(), e);
            }

            log.warn("토스 결제 승인 거절: orderId={}, status={}, code={}, message={}",
                    orderId, status, error.code(), error.message());
            throw new TossConfirmRejectedException(error.code(), error.message());

        } catch (ResourceAccessException e) {
            // 연결 실패 · 응답 타임아웃 · 네트워크 오류
            log.error("토스 결제 승인 호출 실패: orderId={}", orderId, e);
            throw new TossConfirmResultUnknownException("토스 승인 호출 실패 (타임아웃 또는 네트워크 오류)", e);
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
