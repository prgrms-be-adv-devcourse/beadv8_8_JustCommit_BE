package com.justcommit.backend.market.member.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.justcommit.backend.common.messaging.crypto.EventFieldCipher;
import com.justcommit.backend.common.messaging.crypto.EventFieldCipherException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 판매자 배송지 암호문(member.address.events.v1의 encryptedAddresses)을 복호화해 읽는다
 * 이벤트를 받을 때(memberId 확인)와 택배 예약 때 함께 쓴다. 형식은 docs/reference/kafka-topics.md 「member 판매자 배송지」
 * - 필수 칸(memberId · addresses · 주소별 필수 칸)이 없으면 거절. 개수 · 기본 1개 같은 업무 규칙은 member가 보장해 다시 검사하지 않음
 * - 실패 메시지와 원인 예외에 평문 주소 · 암호문을 남기지 않는다 (JSON 변환 예외에는 원문 일부가 들어갈 수 있어 원인을 붙이지 않음)
 * - 주소 형식은 member와의 약속이라 서비스 전역 Jackson 설정과 분리된 JsonMapper를 쓴다
 */
@Component
public class SellerAddressReader {

  private final EventFieldCipher addressEventCipher;
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  public SellerAddressReader(EventFieldCipher addressEventCipher) {
    this.addressEventCipher = addressEventCipher;
  }

  public SellerAddresses read(String encryptedAddresses) {
    String json;
    try {
      json = addressEventCipher.decrypt(encryptedAddresses);
    } catch (EventFieldCipherException e) {
      throw new InvalidSellerAddressException("판매자 배송지 복호화 실패");
    }

    SellerAddresses addresses;
    try {
      addresses = jsonMapper.readValue(json, SellerAddresses.class);
    } catch (JacksonException e) {
      // 필수 칸 누락도 여기로 온다 (record 생성자 예외를 Jackson이 감쌈)
      throw new InvalidSellerAddressException("판매자 배송지 형식 오류");
    }
    if (addresses == null) {
      throw new InvalidSellerAddressException("판매자 배송지 형식 오류");
    }
    return addresses;
  }

  // 암호화 전 주소 JSON. 모르는 칸은 무시해 member가 칸을 추가해도 멈추지 않게
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SellerAddresses(Long memberId, List<SellerAddress> addresses) {

    public SellerAddresses {
      if (memberId == null || addresses == null || addresses.contains(null)) {
        throw new IllegalArgumentException("필수 칸 누락");
      }
      addresses = List.copyOf(addresses);
    }
  }

  // 배송지 하나. address2만 선택 칸
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SellerAddress(Long addressId, Boolean isDefault, String recipientName, String recipientPhone,
                              String zipcode, String address1, String address2) {

    public SellerAddress {
      if (addressId == null || isDefault == null || recipientName == null || recipientPhone == null
          || zipcode == null || address1 == null) {
        throw new IllegalArgumentException("필수 칸 누락");
      }
    }
  }

  // 판매자 배송지를 읽을 수 없음 (복호화 실패 · 형식 오류 · memberId 불일치)/ 메시지에 평문 · 암호문을 넣지 않음
  public static class InvalidSellerAddressException extends RuntimeException {

    public InvalidSellerAddressException(String message) {
      super(message);
    }
  }
}
