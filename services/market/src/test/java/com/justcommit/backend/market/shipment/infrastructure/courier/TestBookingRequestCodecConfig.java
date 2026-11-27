package com.justcommit.backend.market.shipment.infrastructure.courier;

import com.justcommit.backend.common.messaging.crypto.EventFieldCipher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

// 배송 서비스를 띄우는 슬라이스 테스트용: 택배 예약 본문 암호화 도구(#672)를 테스트마다 새 키로 만든다
@TestConfiguration
@Import(CourierBookingRequestCodec.class)
public class TestBookingRequestCodecConfig {

    @Bean
    EventFieldCipher addressEventCipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new EventFieldCipher("k1", Map.of("k1", Base64.getEncoder().encodeToString(key)));
    }
}
