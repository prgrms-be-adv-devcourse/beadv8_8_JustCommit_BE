package com.justcommit.backend.market.config;

import com.justcommit.backend.common.messaging.crypto.EventFieldCipher;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.Map;

/**
 * 판매자 배송지 이벤트(member.address.events.v1) 복호화 도구. member와 같은 키를 쓴다.
 * 키 설정이 잘못되면 EventFieldCipher 생성자에서 실패해 앱이 시작하지 않는다.
 * 전역 지연 초기화 설정과 관계없이 시작할 때 만들도록 @Lazy(false).
 */
@Configuration
@EnableConfigurationProperties(AddressEventCipherConfig.AddressEventCipherProperties.class)
public class AddressEventCipherConfig {

  @Bean
  @Lazy(false)
  public EventFieldCipher addressEventCipher(AddressEventCipherProperties properties) {
    return new EventFieldCipher(properties.currentKey(), properties.keys());
  }

  // messaging.address-event-cipher.current-key / keys.{키 버전}
  @ConfigurationProperties("messaging.address-event-cipher")
  public record AddressEventCipherProperties(String currentKey, Map<String, String> keys) {
  }
}
