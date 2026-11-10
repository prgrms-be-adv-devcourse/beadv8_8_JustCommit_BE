package com.justcommit.backend.member.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

/**
 * 회원 탈퇴 (member.events.v1). 받는 쪽은 복제본을 지우지 않고 탈퇴 상태로 둔다.
 * TODO: 탈퇴 기능을 만들 때 발행
 */
public record MemberWithdrawn(Long memberId) implements IntegrationEvent {

  @Override
  public String topic() {
    return KafkaTopics.MEMBER_EVENTS;
  }

  @Override
  public String eventType() {
    return "MemberWithdrawn";
  }

  @Override
  public String aggregateId() {
    return String.valueOf(memberId);
  }
}
