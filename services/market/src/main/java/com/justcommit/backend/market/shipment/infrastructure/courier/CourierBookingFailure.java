package com.justcommit.backend.market.shipment.infrastructure.courier;

// 택배 예약 실패의 종류 (#671). 예약 큐가 이 값으로 다시 시도할지, 확인 필요로 멈출지 정한다.
public enum CourierBookingFailure {
    // 잠깐 생긴 실패: 5xx · 408 · 429 · 시간 초과 · 연결 실패. 같은 요청을 다시 보내면 성공할 수 있다
    TEMPORARY,
    // 우리 쪽 설정 문제: 401(API 키) · 404(경로 · 주소). 고치면 성공하므로 다시 시도하되 로그로 설정 확인을 알린다
    CONFIG_ERROR,
    // 다시 보내도 같은 결과: 400(요청 내용 · 멱등 키 형식) · 409 IDEMPOTENCY_KEY_REUSED(같은 키에 다른 본문)
    PERMANENT,
    // 409 ORDER_ALREADY_CANCELLED: 예약 전에 같은 주문번호 취소가 택배사에 먼저 기록됨
    ALREADY_CANCELLED;

    static CourierBookingFailure of(int status, String code) {
        if (status == 409 && "ORDER_ALREADY_CANCELLED".equals(code)) {
            return ALREADY_CANCELLED;
        }
        if (status == 401 || status == 404) {
            return CONFIG_ERROR;
        }
        if (status == 400 || status == 409) {
            return PERMANENT;
        }
        // 5xx · 408 · 429와 그 밖에 계약에 없는 응답은 단정하지 않고 다시 시도한다
        return TEMPORARY;
    }

    public boolean isRetryable() {
        return this == TEMPORARY || this == CONFIG_ERROR;
    }
}
