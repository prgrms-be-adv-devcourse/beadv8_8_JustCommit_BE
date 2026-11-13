package com.justcommit.backend.member.config;

import com.justcommit.backend.common.messaging.crypto.EventFieldCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주소 이벤트 암호화 키 설정이 잘못되면 시작을 막는지 확인한다.
 * 설정 클래스와 직접 넣은 값만 쓴다 (application.yml 연결은 MemberApplicationTests, 운영 환경변수 연결은 확인하지 않음).
 */
class AddressEventCipherConfigTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(AddressEventCipherConfig.class)
      .withPropertyValues("messaging.address-event-cipher.current-key=k1");

  @Test
  @DisplayName("정상 키면 EventFieldCipher Bean을 만들고 암호화 · 복호화가 된다")
  void validKey() {
    runner.withPropertyValues("messaging.address-event-cipher.keys.k1=" + key(32))
        .run(context -> {
          assertThat(context).hasSingleBean(EventFieldCipher.class);
          EventFieldCipher cipher = context.getBean(EventFieldCipher.class);
          assertThat(cipher.decrypt(cipher.encrypt("주소"))).isEqualTo("주소");
        });
  }

  @Test
  @DisplayName("키 설정이 없으면 시작하지 않는다")
  void missingKeys() {
    runner.run(context -> {
      assertThat(context).hasFailed();
      assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class);
    });
  }

  @Test
  @DisplayName("환경변수가 비어 풀리지 않은 값이면 시작하지 않는다")
  void unresolvedPlaceholder() {
    // 터미널 환경변수에 영향받지 않도록 어디에도 없는 이름을 쓴다
    runner.withPropertyValues("messaging.address-event-cipher.keys.k1=${ADDRESS_EVENT_KEY_NOT_SET_IN_TEST}")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("환경변수가 설정되지 않았습니다");
        });
  }

  @Test
  @DisplayName("디코딩해 32바이트가 아닌 키면 시작하지 않고, 메시지에 키 값을 남기지 않는다")
  void wrongLengthKey() {
    String shortKey = key(16);
    runner.withPropertyValues("messaging.address-event-cipher.keys.k1=" + shortKey)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("32바이트")
              .hasMessageNotContaining(shortKey);
        });
  }

  private static String key(int bytes) {
    byte[] key = new byte[bytes];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
}
