package com.justcommit.backend.member.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.application.RevocationOutcomeUnknownException;
import com.justcommit.backend.member.auth.application.RevocationResult;
import com.justcommit.backend.member.auth.application.TokenRevocationService;
import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 비밀번호 변경 (#411). 바꾸면 모든 기기 로그아웃 (#409 토큰 폐기).
 * - 트랜잭션을 열지 않는다: revoke()가 회원 행 잠금 · 변경 · 인증 버전 증가 · 폐기 작업 저장을 한 트랜잭션으로 처리
 * - 현재 비밀번호 확인 · 새 비밀번호 저장은 revoke()의 변경 안(잠금 안)에서 한다. 확인에 실패하면 예외로 롤백돼
 *   비밀번호 · 인증 버전 · 폐기 작업이 모두 그대로다
 * - 결과: 커밋됐으면 성공 (Redis 폐기 기록 확인 여부는 revocationCompleted로 따로 전달),
 *   커밋 결과 불명은 503 RESULT_UNKNOWN (클라이언트는 자동으로 다시 요청하지 않음)
 */
@Service
@RequiredArgsConstructor
public class PasswordChangeService {

  private static final String RESULT_UNKNOWN_MESSAGE =
      "비밀번호 변경 결과를 확인하지 못했습니다. 새 비밀번호로 로그인해 확인해 주세요.";

  private final TokenRevocationService tokenRevocationService;
  private final PasswordEncoder passwordEncoder;

  public PasswordChangeResult changePassword(Long memberId, String currentPassword, String newPassword) {
    // 새 비밀번호 해시는 회원 상태와 무관해 잠금 전에 만든다 (잠금 안에서는 현재 비밀번호 확인 한 번만)
    String encodedNewPassword = passwordEncoder.encode(newPassword);
    RevocationResult result;
    try {
      result = tokenRevocationService.revoke(memberId, RevocationReason.PASSWORD_CHANGED, member -> {
        if (member.getStatus() == MemberStatus.DELETED) {
          throw new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND);
        }
        // 소셜 회원은 비밀번호가 없음
        if (member.getPassword() == null) {
          throw new BusinessException(MemberErrorCode.PASSWORD_NOT_SET);
        }
        if (!passwordEncoder.matches(currentPassword, member.getPassword())) {
          throw new BusinessException(MemberErrorCode.INVALID_CURRENT_PASSWORD);
        }
        // 현재 비밀번호가 맞는 것을 확인했으니 문자열 비교로 충분 (BCrypt를 한 번 더 돌리지 않음)
        if (currentPassword.equals(newPassword)) {
          throw new BusinessException(MemberErrorCode.SAME_AS_CURRENT_PASSWORD);
        }
        member.changePassword(encodedNewPassword);
      });
    } catch (RevocationOutcomeUnknownException e) {
      throw new BusinessException(MemberErrorCode.RESULT_UNKNOWN, RESULT_UNKNOWN_MESSAGE);
    }
    return new PasswordChangeResult(result == RevocationResult.COMPLETED);
  }
}
