package com.justcommit.backend.payment.infrastructure.bank;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 모의 은행 서버 설정 (custom.bank.mode=http일 때만 읽는다). 비어 있으면 앱이 시작하지 않는다
@ConfigurationProperties("custom.bank")
public record BankProperties(String baseUrl, String apiKey) {

    public BankProperties {
        if (isBlank(baseUrl)) {
            throw new IllegalStateException("custom.bank.mode=http면 custom.bank.base-url이 필요합니다. (환경변수 BANK_BASE_URL 확인)");
        }
        if (isBlank(apiKey)) {
            throw new IllegalStateException("custom.bank.mode=http면 custom.bank.api-key가 필요합니다. (환경변수 BANK_API_KEY 확인)");
        }
    }

    // 빈 값, 공백, 풀리지 않은 자리 표시자는 값 없음으로 본다
    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || value.startsWith("${");
    }

    @Override
    public String toString() {
        return "BankProperties[baseUrl=" + baseUrl + "]";
    }
}
