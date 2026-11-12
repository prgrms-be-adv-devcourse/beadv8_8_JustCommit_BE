package com.justcommit.backend.member.seller.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.event.MemberUpdated;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.seller.domain.Seller;
import com.justcommit.backend.member.seller.infrastructure.SellerRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// 판매자 등록
@Service
@RequiredArgsConstructor
public class SellerRegisterService {

  private final MemberRepository memberRepository;
  private final SellerRepository sellerRepository;
  private final OutboxPublisher outboxPublisher;

  // 판매자 등록: 회원 잠금 → 이미 판매자인지 확인 → 권한 변경 → 판매자 저장
  // 회원 행을 먼저 잠가 같은 회원의 동시 등록은 두 번째 요청이 앞선 등록 결과를 보고 409가 됨
  @Transactional
  public SellerResult register(SellerRegisterCommand command) {
    Long memberId = command.memberId();

    Member member = memberRepository.findByIdForUpdate(memberId)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

    // 이미 판매자면 409 (잠금을 얻은 뒤에 확인해야 동시 요청의 앞선 등록이 보임)
    if (sellerRepository.existsById(memberId)) {
      throw new BusinessException(MemberErrorCode.ALREADY_SELLER);
    }

    member.promoteToSeller(); // MEMBER → SELLER, ADMIN은 유지. 변경 감지로 커밋 시 UPDATE

    // 잠금으로 막히지만, 혹시 남은 PK 중복은 DB가 막음 -> 409로 변환
    Seller seller;
    try {
      seller = sellerRepository.saveAndFlush(Seller.register(member, command.intro()));
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(MemberErrorCode.ALREADY_SELLER);
    }

    // 다른 서비스의 회원 복제본에 판매자 등록을 알림. ADMIN은 role이 그대로라 seller로 구분
    outboxPublisher.publish(MemberUpdated.from(member, true));

    // sellerId = memberId (SELLER는 회원 id를 PK로 사용)
    return new SellerResult(member.getId(), member.getNickname(), seller.getIntro(), seller.getCreatedAt());
  }
}
