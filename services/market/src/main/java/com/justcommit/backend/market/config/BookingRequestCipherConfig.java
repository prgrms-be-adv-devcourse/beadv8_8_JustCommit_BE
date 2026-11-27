package com.justcommit.backend.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 택배 예약 첫 요청 본문(shipment.booking_request) 암호화 키 설정 (#688). market만 갖는 키다.
 * 판매자 배송지 키(messaging.address-event-cipher)는 member도 갖고 있어, 같이 쓰면 member가 예약 본문을 풀 수 있어 나눴다.
 * 키 버전은 판매자 배송지 키(k1)와 겹치지 않게 b로 시작한다. 암호화 도구는 CourierBookingRequestCodec이 시작할 때 만든다.
 */
@Configuration
@EnableConfigurationProperties(BookingRequestCipherConfig.BookingRequestCipherProperties.class)
public class BookingRequestCipherConfig {

  // custom.courier.booking-request-cipher.current-key / keys.{키 버전}
  @ConfigurationProperties("custom.courier.booking-request-cipher")
  public record BookingRequestCipherProperties(String currentKey, Map<String, String> keys) {
  }
}
