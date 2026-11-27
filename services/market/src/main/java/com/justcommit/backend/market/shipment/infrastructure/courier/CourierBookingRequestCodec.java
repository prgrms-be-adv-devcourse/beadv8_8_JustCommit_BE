package com.justcommit.backend.market.shipment.infrastructure.courier;

import com.justcommit.backend.common.messaging.crypto.EventFieldCipher;
import com.justcommit.backend.common.messaging.crypto.EventFieldCipherException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// 택배 예약 첫 요청 본문을 배송 행(booking_request)에 저장할 문자열로 바꾼다 (#672).
// 판매자 주소가 들어 있어 판매자 배송지 복호화 키(addressEventCipher)로 암호화한다 — 판매자 주소 평문을 DB에 두지 않는 규칙.
// 택배사 본문 모양과 같아야 하므로 서비스 전역 Jackson 설정과 분리된 JsonMapper를 쓴다.
// 실패 메시지에 주소 평문 · 암호문을 남기지 않는다.
@Component
public class CourierBookingRequestCodec {

    private final EventFieldCipher addressEventCipher;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public CourierBookingRequestCodec(EventFieldCipher addressEventCipher) {
        this.addressEventCipher = addressEventCipher;
    }

    public String encode(CourierBookingRequest request) {
        return addressEventCipher.encrypt(jsonMapper.writeValueAsString(request));
    }

    public CourierBookingRequest decode(String encoded) {
        try {
            return jsonMapper.readValue(addressEventCipher.decrypt(encoded), CourierBookingRequest.class);
        } catch (EventFieldCipherException | JacksonException e) {
            // 원인 예외에 원문 일부가 들어갈 수 있어 붙이지 않는다
            throw new IllegalStateException("저장한 택배 예약 본문을 읽지 못함: " + e.getClass().getSimpleName());
        }
    }
}
