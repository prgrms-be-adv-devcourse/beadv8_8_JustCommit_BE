package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberAccount;
import com.justcommit.backend.member.MemberQuery;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// MemberQuery 구현 (다른 모듈은 이 클래스를 모르고 MemberQuery 인터페이스만 사용)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberQueryService implements MemberQuery {

  private final MemberRepository memberRepository;
  private final AccountCipher accountCipher;

  @Override
  public MemberAccount getAccount(Long memberId) {
    Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

    return new MemberAccount(
            member.getBankCode(),
            accountCipher.decrypt(member.getAccountNoEnc()), // 필요한 순간에만 복호화
            member.getAccountHolder()
    );
  }
}
