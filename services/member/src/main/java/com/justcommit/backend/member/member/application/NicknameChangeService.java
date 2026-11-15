package com.justcommit.backend.member.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.event.MemberUpdated;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.seller.infrastructure.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 닉네임 변경 (#414).
 * - 회원 행을 잠가 같은 회원의 닉네임 변경 · 판매자 등록과 순서를 맞춘다
 *   (받는 쪽 복제본은 이벤트 시각으로 최신을 판단하므로, 커밋 순서와 이벤트 순서가 어긋나지 않게)
 * - 지금 닉네임과 같으면 바꾸지 않고 이벤트도 보내지 않는다
 * - 다른 회원 두 명이 동시에 같은 닉네임을 고르면 둘 다 중복 확인을 통과할 수 있어,
 *   DB UNIQUE 제약 위반을 409 DUPLICATE_NICKNAME으로 바꾼다 (가입과 같은 방식)
 * - 변경과 MemberUpdated 발행(Outbox)은 같은 트랜잭션
 */
@Service
@RequiredArgsConstructor
public class NicknameChangeService {

  private final MemberRepository memberRepository;
  private final SellerRepository sellerRepository;
  private final OutboxPublisher outboxPublisher;

  @Transactional
  public NicknameChangeResult changeNickname(Long memberId, String nickname) {
    Member member = memberRepository.findByIdForUpdate(memberId)
        .filter(m -> m.getStatus() != MemberStatus.DELETED)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

    if (member.getNickname().equals(nickname)) {
      return new NicknameChangeResult(member.getId(), member.getNickname());
    }
    if (memberRepository.existsByNickname(nickname)) {
      throw new BusinessException(MemberErrorCode.DUPLICATE_NICKNAME);
    }

    member.changeNickname(nickname);
    // 제약 위반이 커밋 때가 아니라 여기서 나도록 먼저 DB에 보냄
    try {
      memberRepository.flush();
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(toDuplicateErrorCode(e));
    }

    // 잠금을 잡은 상태에서 읽어 판매자 등록과 엇갈리지 않음. ADMIN은 role만으로 판매자인지 알 수 없어 행으로 확인
    boolean seller = sellerRepository.existsById(memberId);
    outboxPublisher.publish(MemberUpdated.from(member, seller));
    return new NicknameChangeResult(member.getId(), member.getNickname());
  }

  // 닉네임 제약만 409로 바꾸고, 예상하지 못한 제약 위반은 그대로 던짐 (500 + 로그)
  private MemberErrorCode toDuplicateErrorCode(DataIntegrityViolationException e) {
    String message = String.valueOf(e.getMostSpecificCause().getMessage());
    if (message.contains("uk_member_nickname")) {
      return MemberErrorCode.DUPLICATE_NICKNAME;
    }
    throw e;
  }
}
