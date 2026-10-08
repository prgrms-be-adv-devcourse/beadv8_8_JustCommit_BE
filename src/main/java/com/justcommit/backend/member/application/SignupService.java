package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.redis.EmailVerificationRedisStore;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

// 회원가입·닉네임 중복 확인
@Service
@RequiredArgsConstructor
public class SignupService {
  private final MemberRepository memberRepository;
  private final EmailVerificationRedisStore verificationStore;
  private final PasswordEncoder passwordEncoder;

  // 회원가입: 이메일 인증 확인 → 중복 검사 → 저장 → 인증 완료 상태 삭제
  @Transactional
  public SignupResult signup(SignupCommand command) {
    String email = normalizeEmail(command.email());

    // 인증 없이 가입 API 직접 호출해도 여기서 막힘
    if (!verificationStore.isVerified(email)) {
      throw new BusinessException(MemberErrorCode.EMAIL_NOT_VERIFIED);
    }

    // 1. 먼저 조회해서 어떤 값이 겹쳤는지 알려 줌
    if (memberRepository.existsByEmail(email)) {
      throw new BusinessException(MemberErrorCode.DUPLICATE_EMAIL);
    }
    if (memberRepository.existsByNickname(command.nickname())) {
      throw new BusinessException(MemberErrorCode.DUPLICATE_NICKNAME);
    }
    if (memberRepository.existsByPhone(command.phone())) {
      throw new BusinessException(MemberErrorCode.DUPLICATE_PHONE);
    }

    Member member = Member.createLocal(
            email,
            passwordEncoder.encode(command.password()),
            command.nickname(),
            command.phone());

    // 2. 동시 가입으로 위 검사를 둘 다 통과한 경우 DB UNIQUE 제약이 막음 -> 409로 변환
    try {
      memberRepository.saveAndFlush(member);
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(toDuplicateErrorCode(e));
    }

    verificationStore.deleteVerified(email);  // 같은 인증으로 다시 가입x
    return new SignupResult(member.getId(), member.getNickname());
  }

  // 닉네임 사용 가능 여부 (중복 확인 API)
  @Transactional(readOnly = true)
  public boolean isNicknameAvailable(String nickname) {
    return !memberRepository.existsByNickname(nickname);
  }

  // DB가 알려 준 제약 이름으로 어떤 값이 겹쳤는지 구분
  private MemberErrorCode toDuplicateErrorCode(DataIntegrityViolationException e) {
    String message = String.valueOf(e.getMostSpecificCause().getMessage());
    if (message.contains("uk_member_email")) {
      return MemberErrorCode.DUPLICATE_EMAIL;
    }
    if (message.contains("uk_member_nickname")) {
      return MemberErrorCode.DUPLICATE_NICKNAME;
    }
    if (message.contains("uk_member_phone")) {
      return MemberErrorCode.DUPLICATE_PHONE;
    }
    throw e;  // 예상하지 못한 제약 위반은 숨기지 않고 그대로 던짐 (500 + 로그)
  }

  // EmailVerificationService와 같은 규칙 (Redis 키·DB 값이 일치해야 함)
  private String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }
}
