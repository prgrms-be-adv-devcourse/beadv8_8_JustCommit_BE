package com.justcommit.backend.market.member.application;

import com.justcommit.backend.common.messaging.crypto.EventFieldCipher;
import com.justcommit.backend.market.member.application.SellerAddressReader.InvalidSellerAddressException;
import com.justcommit.backend.market.member.application.SellerAddressReader.SellerAddress;
import com.justcommit.backend.market.member.application.SellerAddressReader.SellerAddresses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SellerAddressReaderTest {

  private static final String ADDRESS1 = "서울 강남구 테헤란로 1";

  private final EventFieldCipher cipher = newCipher();
  private final SellerAddressReader reader = new SellerAddressReader(cipher);

  @Test
  @DisplayName("암호문을 풀어 판매자 배송지 목록을 읽는다 (address2 null, isDefault false 허용, 모르는 칸 무시)")
  void read() {
    String json = """
        {"memberId":7,"unknown":"무시","addresses":[
          {"addressId":15,"isDefault":true,"recipientName":"홍길동","recipientPhone":"01012345678",
           "zipcode":"06236","address1":"%s","address2":"5층","extra":1},
          {"addressId":12,"isDefault":false,"recipientName":"홍길동","recipientPhone":"01012345678",
           "zipcode":"04524","address1":"서울 중구 세종대로 110","address2":null}]}
        """.formatted(ADDRESS1);

    SellerAddresses addresses = reader.read(cipher.encrypt(json));

    assertThat(addresses.memberId()).isEqualTo(7L);
    assertThat(addresses.addresses()).hasSize(2);
    SellerAddress first = addresses.addresses().get(0);
    assertThat(first.addressId()).isEqualTo(15L);
    assertThat(first.isDefault()).isTrue();
    assertThat(first.zipcode()).isEqualTo("06236");
    assertThat(first.address1()).isEqualTo(ADDRESS1);
    assertThat(first.address2()).isEqualTo("5층");
    SellerAddress second = addresses.addresses().get(1);
    assertThat(second.isDefault()).isFalse();
    assertThat(second.address2()).isNull();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @DisplayName("필수 구조가 없거나 null이면 거절한다")
  @ValueSource(strings = {
      // memberId 없음 · null
      "{\"addresses\":[]}",
      "{\"memberId\":null,\"addresses\":[]}",
      // addresses 없음 · null · 목록 안에 null
      "{\"memberId\":7}",
      "{\"memberId\":7,\"addresses\":null}",
      "{\"memberId\":7,\"addresses\":[null]}",
      // 주소 필수 칸(address1) 없음
      "{\"memberId\":7,\"addresses\":[{\"addressId\":1,\"isDefault\":true,\"recipientName\":\"홍길동\",\"recipientPhone\":\"01012345678\",\"zipcode\":\"06236\"}]}",
      // isDefault 없음 · null (false와 구분)
      "{\"memberId\":7,\"addresses\":[{\"addressId\":1,\"recipientName\":\"홍길동\",\"recipientPhone\":\"01012345678\",\"zipcode\":\"06236\",\"address1\":\"서울 강남구 테헤란로 1\"}]}",
      "{\"memberId\":7,\"addresses\":[{\"addressId\":1,\"isDefault\":null,\"recipientName\":\"홍길동\",\"recipientPhone\":\"01012345678\",\"zipcode\":\"06236\",\"address1\":\"서울 강남구 테헤란로 1\"}]}",
      // JSON 자체가 null
      "null"
  })
  void rejectsMissingRequired(String json) {
    InvalidSellerAddressException e = catchThrowableOfType(InvalidSellerAddressException.class,
        () -> reader.read(cipher.encrypt(json)));

    assertThat(e).isNotNull();
    assertNoPlaintext(e);
  }

  @Test
  @DisplayName("다른 키로 만든 암호문은 복호화 실패로 거절한다")
  void rejectsOtherKey() {
    String json = "{\"memberId\":7,\"addresses\":[{\"addressId\":1,\"isDefault\":true,\"recipientName\":\"홍길동\","
        + "\"recipientPhone\":\"01012345678\",\"zipcode\":\"06236\",\"address1\":\"" + ADDRESS1 + "\"}]}";
    String fromOtherKey = newCipher().encrypt(json);

    InvalidSellerAddressException e = catchThrowableOfType(InvalidSellerAddressException.class,
        () -> reader.read(fromOtherKey));

    assertThat(e).isNotNull();
    assertNoPlaintext(e);
  }

  @Test
  @DisplayName("깨진 암호문은 복호화 실패로 거절한다")
  void rejectsBrokenCiphertext() {
    InvalidSellerAddressException e = catchThrowableOfType(InvalidSellerAddressException.class,
        () -> reader.read("v1.k1:not-base64!!"));

    assertThat(e).isNotNull();
    assertThat(e.getCause()).isNull();
  }

  // 실패 메시지와 원인 예외에 평문 주소가 남지 않아야 한다
  private static void assertNoPlaintext(InvalidSellerAddressException e) {
    assertThat(e.getMessage()).doesNotContain(ADDRESS1).doesNotContain("홍길동").doesNotContain("01012345678");
    assertThat(e.getCause()).isNull();
  }

  private static EventFieldCipher newCipher() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return new EventFieldCipher("k1", Map.of("k1", Base64.getEncoder().encodeToString(key)));
  }
}
