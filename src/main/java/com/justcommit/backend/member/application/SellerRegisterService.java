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

@Service
@RequiredArgsConstructor
public class SellerRegisterService {

  private final MemberRepository memberRepository;
  private final SellerRepository sellerRepository;

  @Transactional
  public void register(SellerRegisterCommand command) {
    Long memberId = command.memberId();

    if (sellerRepository.existsById(memberId)) {
      throw new BusinessException(MemberErrorCode.ALREADY_SELLER);
    }

    Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

    member.promoteToSeller(); // 변경 감지로 커밋 시 UPDATE

    try {
      sellerRepository.saveAndFlush(Seller.register(member, command.intro()));
    } catch (DataIntegrityViolationException e) {
      // 동시에 두 번 요청하면 둘 다 existsById를 통과할 수 있음 → PK 중복으로 막히면 같은 409
      throw new BusinessException(MemberErrorCode.ALREADY_SELLER);
    }
  }
}
