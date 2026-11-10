package com.justcommit.backend.market.client.member;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.market.member.domain.MemberReplica;
import com.justcommit.backend.market.member.infrastructure.MemberReplicaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

// 회원 정보는 member를 부르지 않고 market의 회원 복제본(member.events.v1로 채움)에서 읽는다 (#275)
@Slf4j
@Component
@RequiredArgsConstructor
class ReplicaMemberClient implements MemberQuery {

  static final String WITHDRAWN_NICKNAME = "탈퇴한 회원";
  // TODO: 복제본이 아직 없을 때 표시 문구 · member REST 보완 여부 정한 뒤 확정
  static final String UNKNOWN_NICKNAME = "알 수 없는 회원";

  private final MemberReplicaRepository memberReplicaRepository;

  // shortcut: 판매자 기본 배송지 복제본(member.address.events.v1)이 생기기 전이라 503, #275 후속에서 교체
  @Override
  public MemberAddress getDefaultAddress(Long memberId) {
    throw new BusinessException(CommonErrorCode.INTERNAL_API_UNAVAILABLE);
  }

  // 복제본이 아직 없으면(이벤트가 안 옴) 503 대신 기본 문구. 닉네임은 화면 표시용이라 장바구니 · 주문을 막지 않게
  @Override
  public Map<Long, String> getNicknames(Collection<Long> memberIds) {
    Map<Long, String> nicknames = new HashMap<>();
    for (MemberReplica replica : memberReplicaRepository.findAllById(memberIds)) {
      nicknames.put(replica.getMemberId(), displayName(replica));
    }
    for (Long memberId : memberIds) {
      if (!nicknames.containsKey(memberId)) {
        log.warn("회원 복제본이 없어 기본 문구로 표시: memberId={}", memberId);
        nicknames.put(memberId, UNKNOWN_NICKNAME);
      }
    }
    return nicknames;
  }

  private static String displayName(MemberReplica replica) {
    if (replica.isWithdrawn()) {
      return WITHDRAWN_NICKNAME;
    }
    // 탈퇴 표시 없이 닉네임만 빈 경우는 정상 흐름에선 없지만, 빈칸으로 보이지 않게
    return replica.getNickname() != null ? replica.getNickname() : UNKNOWN_NICKNAME;
  }
}
