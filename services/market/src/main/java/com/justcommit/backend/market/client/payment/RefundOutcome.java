package com.justcommit.backend.market.client.payment;

// 환불 호출 한 번의 결과. 예외 대신 이 값으로 알린다 (#282, 결제의 PaymentOutcome과 같은 방식)
// reason은 주문상품 last_error에 그대로 남기는 원인 코드다 (TIMEOUT, HTTP_503, CONNECTION_FAILED 등)
public record RefundOutcome(Type type, String reason) {

    private static final int MAX_REASON_LENGTH = 100;

    public enum Type {
        // REFUNDED, ALREADY_REFUNDED
        REFUNDED,
        // payment가 환불을 거절함이 확실하다: 4xx(401, 403 포함). 재시도하지 않는다
        REJECTED,
        // 결과 불명: 응답 타임아웃, 5xx, 연결 실패, 본문 읽기 실패, 모르는 상태 값. 같은 키로 다시 요청한다
        UNKNOWN
    }

    public RefundOutcome {
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH);
        }
    }

    public static RefundOutcome refunded() {
        return new RefundOutcome(Type.REFUNDED, null);
    }

    public static RefundOutcome rejected(String reason) {
        return new RefundOutcome(Type.REJECTED, reason);
    }

    public static RefundOutcome unknown(String reason) {
        return new RefundOutcome(Type.UNKNOWN, reason);
    }

    // 상품 금액 환불과 배송비 환불을 합친다. 하나라도 거절이면 거절, 하나라도 모르면 결과 불명
    public RefundOutcome and(RefundOutcome other) {
        if (other == null || type == Type.REJECTED) {
            return this;
        }
        if (other.type == Type.REJECTED) {
            return other;
        }
        return type == Type.UNKNOWN ? this : other;
    }
}
