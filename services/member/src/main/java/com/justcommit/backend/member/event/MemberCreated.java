package com.justcommit.backend.member.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.member.domain.Member;

/**
 * 회원 가입 (member.events.v1). 받는 쪽은 회원 복제본을 만들거나 덮어쓴다 (upsert).
 * payload 칸은 MemberUpdated와 같다 (바뀐 뒤 전체 값, #339 product 담당과 합의).
 */
public record MemberCreated(Long memberId, String nickname, String role, boolean seller)
    implements IntegrationEvent {

  public static MemberCreated from(Member member, boolean seller) {
    return new MemberCreated(member.getId(), member.getNickname(), member.getRole().name(), seller);
  }

  @Override
  public String topic() {
    return KafkaTopics.MEMBER_EVENTS;
  }

  @Override
  public String eventType() {
    return "MemberCreated";
  }

  @Override
  public String aggregateId() {
    return String.valueOf(memberId);
  }
}
