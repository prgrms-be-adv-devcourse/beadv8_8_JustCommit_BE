package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Seller;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.repository.SellerRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// 판매자 등록
@Service
@RequiredArgsConstructor
public class SellerRegisterService {

  private final MemberRepository memberRepository;
  private final SellerRepository sellerRepository;

  // 판매자 등록: 판매자 여부 확인 → 회원 조회 → 권한 변경 → 판매자 저장
  @Transactional
  public SellerResult register(SellerRegisterCommand command) {
    Long memberId = command.memberId();

    // 이미 판매자면 409
    if (sellerRepository.existsById(memberId)) {
      throw new BusinessException(MemberErrorCode.ALREADY_SELLER);
    }

    Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

    member.promoteToSeller(); // MEMBER → SELLER, ADMIN은 유지. 변경 감지로 커밋 시 UPDATE

    // 동시 요청으로 위 검사를 둘 다 통과한 경우 PK 중복을 DB가 막음 -> 409로 변환
    Seller seller;
    try {
      seller = sellerRepository.saveAndFlush(Seller.register(member, command.intro()));
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(MemberErrorCode.ALREADY_SELLER);
    }

    // sellerId = memberId (SELLER는 회원 id를 PK로 사용)
    return new SellerResult(member.getId(), member.getNickname(), seller.getIntro(), seller.getCreatedAt());
  }
}
