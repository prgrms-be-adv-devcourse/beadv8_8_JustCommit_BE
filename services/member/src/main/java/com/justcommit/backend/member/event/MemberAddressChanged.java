package com.justcommit.backend.member.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

/**
 * 판매자 배송지 목록 변경 (member.address.events.v1). market이 택배 보내는 주소로 쓴다.
 * 판매자 등록 · 배송지 추가 · 기본 배송지 변경 때 바뀐 뒤 전체 목록을 보낸다.
 * encryptedAddresses는 SellerAddressesPayload를 JSON으로 만든 뒤 EventFieldCipher로 암호화한 값이다 (v1.k1:...).
 * 받는 쪽은 복호화한 memberId가 이 이벤트의 memberId와 같은지 반드시 확인한다 (다른 회원 암호문 교체 방지).
 */
public record MemberAddressChanged(Long memberId, String encryptedAddresses) implements IntegrationEvent {

  @Override
  public String topic() {
    return KafkaTopics.MEMBER_ADDRESS_EVENTS;
  }

  @Override
  public String eventType() {
    return "MemberAddressChanged";
  }

  @Override
  public String aggregateId() {
    return String.valueOf(memberId);
  }
}
