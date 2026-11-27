package com.justcommit.backend.market.shipment.infrastructure.courier;

import com.justcommit.backend.common.messaging.crypto.EventFieldCipher;
import com.justcommit.backend.common.messaging.crypto.EventFieldCipherException;
import com.justcommit.backend.market.config.BookingRequestCipherConfig.BookingRequestCipherProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// 택배 예약 첫 요청 본문을 배송 행(booking_request)에 저장할 문자열로 바꾼다 (#672).
// 판매자 주소가 들어 있어 market 전용 키(custom.courier.booking-request-cipher)로 암호화한다 (#688).
// 택배사 본문 모양과 같아야 하므로 서비스 전역 Jackson 설정과 분리된 JsonMapper를 쓴다.
// 실패 메시지에 주소 평문 · 암호문을 남기지 않는다.
// 키 설정이 잘못되면 생성자에서 실패해 앱이 시작하지 않도록 전역 지연 초기화와 관계없이 바로 만든다.
@Component
@Lazy(false)
public class CourierBookingRequestCodec {

    private final EventFieldCipher bookingRequestCipher;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public CourierBookingRequestCodec(BookingRequestCipherProperties properties) {
        this.bookingRequestCipher = new EventFieldCipher(properties.currentKey(), properties.keys());
    }

    public String encode(CourierBookingRequest request) {
        return bookingRequestCipher.encrypt(jsonMapper.writeValueAsString(request));
    }

    public CourierBookingRequest decode(String encoded) {
        try {
            return jsonMapper.readValue(bookingRequestCipher.decrypt(encoded), CourierBookingRequest.class);
        } catch (EventFieldCipherException | JacksonException e) {
            // 원인 예외에 원문 일부가 들어갈 수 있어 붙이지 않는다
            throw new IllegalStateException("저장한 택배 예약 본문을 읽지 못함: " + e.getClass().getSimpleName());
        }
    }
}
