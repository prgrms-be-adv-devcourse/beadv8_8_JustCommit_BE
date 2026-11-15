package com.justcommit.backend.member.seller.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.seller.domain.Seller;
import com.justcommit.backend.member.seller.infrastructure.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 상점 정보 (#417).
 * - 공개 조회: 누구나. 판매자가 아니거나 없는 회원 · 탈퇴 회원은 모두 404 SELLER_NOT_FOUND
 *   (탈퇴한 판매자를 어떻게 보일지는 탈퇴 Feature #405에서 다르게 정하면 바꿈)
 * - 소개글 수정: 판매자 본인만 (인증 정보의 회원 ID), 빈 문자열은 NULL로 저장
 */
@Service
@RequiredArgsConstructor
public class SellerProfileService {

  private final SellerRepository sellerRepository;
  private final MemberRepository memberRepository;

  @Transactional(readOnly = true)
  public SellerResult getSeller(Long sellerId) {
    Seller seller = sellerRepository.findWithMemberById(sellerId)
        .filter(s -> s.getMember().getStatus() != MemberStatus.DELETED)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.SELLER_NOT_FOUND));
    return toResult(seller);
  }

  // 본인 소개글 수정: 탈퇴 회원은 404 MEMBER_NOT_FOUND, 판매자 행이 없으면 403 NOT_SELLER
  // 마지막에 저장된 값이 남는 정책이라 잠금 · 이벤트 없음 (소개글은 MemberUpdated에 없음)
  @Transactional
  public SellerResult changeIntro(Long memberId, String intro) {
    Member member = memberRepository.findById(memberId)
        .filter(m -> m.getStatus() != MemberStatus.DELETED)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    Seller seller = sellerRepository.findById(memberId)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.NOT_SELLER));
    seller.changeIntro(intro);
    return new SellerResult(member.getId(), member.getNickname(), seller.getIntro(), seller.getCreatedAt());
  }

  // sellerId = memberId (판매자는 회원 id를 PK로 사용)
  private static SellerResult toResult(Seller seller) {
    return new SellerResult(seller.getMemberId(), seller.getMember().getNickname(),
        seller.getIntro(), seller.getCreatedAt());
  }
}
