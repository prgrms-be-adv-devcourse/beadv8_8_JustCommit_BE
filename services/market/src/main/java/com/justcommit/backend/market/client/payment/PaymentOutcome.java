package com.justcommit.backend.market.client.payment;

// 결제 호출 한 번의 결과. 예외 대신 이 값으로 알린다 (ADR 0023)
// 첫 호출인지 복구인지에 따른 주문 상태는 주문 쪽이 정한다 (NOT_DELIVERED: 첫 호출이면 확정 실패, 복구면 결과 불명)
// reason은 주문 last_error에 그대로 남기는 원인 코드다 (TIMEOUT, HTTP_503, CONNECTION_FAILED 등)
public record PaymentOutcome(Type type, String reason) {

    private static final int MAX_REASON_LENGTH = 100;

    public enum Type {
        // PAID · ALREADY_PAID
        PAID,
        // 잔액 부족: 결제 대기로 돌아간다
        INSUFFICIENT_BALANCE,
        // payment가 결제를 거절함이 확실하다: 지갑 없음 · 4xx(401 · 403 포함)
        REJECTED,
        // 연결 실패(재시도 1번 뒤). 이번 요청은 payment에 닿지 않았다
        NOT_DELIVERED,
        // 결과 불명: 응답 타임아웃 · 5xx · 본문 읽기 실패 · 모르는 상태 값
        UNKNOWN
    }

    public PaymentOutcome {
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH);
        }
    }

    public static PaymentOutcome paid() {
        return new PaymentOutcome(Type.PAID, null);
    }

    public static PaymentOutcome insufficientBalance() {
        return new PaymentOutcome(Type.INSUFFICIENT_BALANCE, null);
    }

    public static PaymentOutcome rejected(String reason) {
        return new PaymentOutcome(Type.REJECTED, reason);
    }

    public static PaymentOutcome notDelivered() {
        return new PaymentOutcome(Type.NOT_DELIVERED, "CONNECTION_FAILED");
    }

    public static PaymentOutcome unknown(String reason) {
        return new PaymentOutcome(Type.UNKNOWN, reason);
    }
}
