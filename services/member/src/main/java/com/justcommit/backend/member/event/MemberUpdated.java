package com.justcommit.backend.member.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.member.domain.Member;

/**
 * 회원 정보 변경 (member.events.v1): 판매자 등록, 닉네임 변경(API가 생기면).
 * 바뀐 칸만이 아니라 바뀐 뒤 전체 값을 보낸다 (받는 쪽은 upsert).
 */
public record MemberUpdated(Long memberId, String nickname, String role, boolean seller)
    implements IntegrationEvent {

  public static MemberUpdated from(Member member, boolean seller) {
    return new MemberUpdated(member.getId(), member.getNickname(), member.getRole().name(), seller);
  }

  @Override
  public String topic() {
    return KafkaTopics.MEMBER_EVENTS;
  }

  @Override
  public String eventType() {
    return "MemberUpdated";
  }

  @Override
  public String aggregateId() {
    return String.valueOf(memberId);
  }
}
