package com.justcommit.backend.payment.infrastructure.bank;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;

// 모의 은행 서버로 지급한다 (custom.bank.mode=http, 빈 등록은 BankClientConfig)
// - 200 + transferId → 성공
// - 그 밖의 모든 응답(422, 409, 401, 400, 5xx), 연결 실패, 타임아웃 → BankTransferException
// 원래 HTTP 예외는 cause로 붙이지 않는다 (메시지에 응답 본문이 들어감)
@Slf4j
@RequiredArgsConstructor
public class RestBankTransferClient implements BankTransferClient {

    private final BankApi bankApi;
    private final BankProperties properties;

    @Override
    public BankTransferResult transfer(BankTransferRequest request) {
        String key = request.idempotencyKey();
        BankApi.TransferBody body = new BankApi.TransferBody(
                request.bankCode(), request.accountNo(), request.accountHolder(), toLong(key, request.amount()));

        BankApi.BankResponse response;
        try {
            response = bankApi.transfer(properties.apiKey(), key, body);
        } catch (RestClientResponseException e) {
            throw fail(key, describe(e));
        } catch (RestClientException e) {
            throw fail(key, "결과 불명, cause=" + e.getClass().getSimpleName());
        }

        String transferId = response != null && response.data() != null ? response.data().transferId() : null;
        if (transferId == null || transferId.isBlank()) {
            throw fail(key, "200 응답에 transferId 없음");
        }
        return new BankTransferResult(key, transferId);
    }

    private static long toLong(String key, BigDecimal amount) {
        try {
            return amount.longValueExact();
        } catch (ArithmeticException e) {
            throw fail(key, "금액이 정수가 아님");
        }
    }

    // 422면 은행이 기록한 실패 이유(failureCode)를 함께 남긴다
    private static String describe(RestClientResponseException e) {
        BankApi.BankResponse error = readBody(e);
        StringBuilder detail = new StringBuilder("status=").append(e.getStatusCode().value());
        if (error != null && error.code() != null) {
            detail.append(", code=").append(error.code());
        }
        if (error != null && error.data() != null && error.data().failureCode() != null) {
            detail.append(", failureCode=").append(error.data().failureCode());
        }
        return detail.toString();
    }

    private static BankApi.BankResponse readBody(RestClientResponseException e) {
        try {
            return e.getResponseBodyAs(BankApi.BankResponse.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static BankTransferException fail(String key, String detail) {
        String message = "은행 지급 실패: idempotencyKey=" + key + ", " + detail;
        log.warn(message);
        return new BankTransferException(message);
    }
}
