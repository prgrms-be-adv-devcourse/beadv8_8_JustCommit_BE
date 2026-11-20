package com.justcommit.backend.member.seller.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
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
  private final MemberLocker memberLocker;

  @Transactional(readOnly = true)
  public SellerResult getSeller(Long sellerId) {
    Seller seller = sellerRepository.findWithMemberById(sellerId)
        .filter(s -> s.getMember().getStatus() != MemberStatus.DELETED)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.SELLER_NOT_FOUND));
    return toResult(seller);
  }

  // 본인 소개글 수정: 탈퇴 회원은 404 MEMBER_NOT_FOUND, 판매자 행이 없으면 403 NOT_SELLER. 이벤트 없음 (소개글은 MemberUpdated에 없음)
  // 탈퇴(#407)와 같은 회원 행 잠금을 먼저 잡는다: 탈퇴가 소개글을 지운 뒤에 옛 상태로 읽은 수정이 다시 저장하지 않게.
  // 수정이 먼저 끝나면 탈퇴가 그 값을 지우고, 탈퇴가 먼저 끝나면 수정은 404 (대기 제한을 넘으면 409 MEMBER_BUSY)
  @Transactional
  public SellerResult changeIntro(Long memberId, String intro) {
    Member member = memberLocker.lockById(memberId)
        .filter(m -> m.getStatus() == MemberStatus.ACTIVE) // 탈퇴 · 카카오 연결 해제(INACTIVE, #422) 회원은 404
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
