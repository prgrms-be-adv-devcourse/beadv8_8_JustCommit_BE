package com.justcommit.backend.member.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.application.RevocationOutcomeUnknownException;
import com.justcommit.backend.member.auth.application.RevocationResult;
import com.justcommit.backend.member.auth.application.TokenRevocationService;
import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.auth.infrastructure.redis.PasswordFailureStore;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.domain.exception.PasswordChangeLockedException;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 비밀번호 변경 (#411). 바꾸면 모든 기기 로그아웃 (#409 토큰 폐기).
 * - 트랜잭션을 열지 않는다: revoke()가 회원 행 잠금 · 변경 · 인증 버전 증가 · 폐기 작업 저장을 한 트랜잭션으로 처리
 * - 현재 비밀번호 확인 · 새 비밀번호 저장은 revoke()의 변경 안(잠금 안)에서 한다. 확인에 실패하면 예외로 롤백돼
 *   비밀번호 · 인증 버전 · 폐기 작업이 모두 그대로다
 * - 결과: 커밋됐으면 성공 (Redis 폐기 기록 확인 여부는 revocationCompleted로 따로 전달),
 *   커밋 결과 불명은 503 RESULT_UNKNOWN (클라이언트는 자동으로 다시 요청하지 않음)
 * - 현재 비밀번호 실패 제한 (#412): 같은 인증 버전에서 5번 틀리면 첫 오답부터 24시간 동안 429 PASSWORD_CHANGE_LOCKED.
 *   잠금 전에 한 번(막혔으면 BCrypt 없이), 잠금 안에서 현재 비밀번호 확인 직전에 한 번 더 확인하고,
 *   틀리면 잠금을 풀기 전에 기록한다 (동시 요청도 같은 한도를 따름). 확인 · 기록에 실패하면 변경을 중단한다
 * - 커밋되면(COMPLETED · PENDING) 잠금 안에서 확인한 변경 전 버전의 실패 횟수만 지운다.
 *   지우지 못해도 새 인증 버전에는 옛 횟수가 적용되지 않으므로 성공으로 응답한다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordChangeService {

  // 현재 비밀번호 실패 한도. 탈퇴 재확인(#407)도 같은 횟수 · 기간을 공유한다
  public static final int MAX_CURRENT_PASSWORD_FAILURES = 5;
  public static final Duration FAILURE_WINDOW = Duration.ofDays(1);
  private static final String RESULT_UNKNOWN_MESSAGE =
      "비밀번호 변경 결과를 확인하지 못했습니다. 새 비밀번호로 로그인해 확인해 주세요.";

  private final TokenRevocationService tokenRevocationService;
  private final PasswordEncoder passwordEncoder;
  private final MemberRepository memberRepository;
  private final PasswordFailureStore failureStore;

  public PasswordChangeResult changePassword(Long memberId, String currentPassword, String newPassword) {
    // 잠금 전 확인: 지금 인증 버전으로 막혔으면 새 비밀번호를 해시하지 않고 429 (회원이 없으면 잠금 안에서 기존대로 처리)
    memberRepository.findById(memberId).ifPresent(member -> requireNotLocked(memberId, member.getTokenVersion()));
    // 새 비밀번호 해시는 회원 상태와 무관해 잠금 전에 만든다 (잠금 안에서는 현재 비밀번호 확인 한 번만)
    String encodedNewPassword = passwordEncoder.encode(newPassword);
    // 잠금 안에서 확인한 변경 전 인증 버전 (잠금 전 조회와 실제 변경 사이에 버전이 달라질 수 있어 잠금 안의 값을 쓴다)
    AtomicInteger versionBeforeChange = new AtomicInteger();
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
        // 잠근 회원의 버전으로 다시 확인: 잠금 전 확인 뒤 다른 요청이 오답을 기록했을 수 있다
        requireNotLocked(memberId, member.getTokenVersion());
        if (!passwordEncoder.matches(currentPassword, member.getPassword())) {
          // 잠금을 풀기 전에 기록해 동시 요청도 같은 한도를 따른다 (Redis라 DB 롤백과 무관하게 남음)
          failureStore.recordFailure(memberId, member.getTokenVersion(), FAILURE_WINDOW);
          throw new BusinessException(MemberErrorCode.INVALID_CURRENT_PASSWORD);
        }
        // 현재 비밀번호가 맞는 것을 확인했으니 문자열 비교로 충분 (BCrypt를 한 번 더 돌리지 않음). 실패 횟수에 넣지 않는다
        if (currentPassword.equals(newPassword)) {
          throw new BusinessException(MemberErrorCode.SAME_AS_CURRENT_PASSWORD);
        }
        versionBeforeChange.set(member.getTokenVersion());
        member.changePassword(encodedNewPassword);
      });
    } catch (RevocationOutcomeUnknownException e) {
      // 커밋 여부를 모르므로 실패 횟수를 지우지 않는다
      throw new BusinessException(MemberErrorCode.RESULT_UNKNOWN, RESULT_UNKNOWN_MESSAGE);
    }
    clearFailures(memberId, versionBeforeChange.get());
    return new PasswordChangeResult(result == RevocationResult.COMPLETED);
  }

  // 같은 인증 버전에서 한도만큼 틀렸으면 429 + 남은 초
  private void requireNotLocked(Long memberId, int tokenVersion) {
    long retryAfterSeconds = failureStore.retryAfterSeconds(memberId, tokenVersion, MAX_CURRENT_PASSWORD_FAILURES);
    if (retryAfterSeconds > 0) {
      throw new PasswordChangeLockedException(retryAfterSeconds);
    }
  }

  // 커밋 뒤 변경 전 버전의 실패 횟수만 지운다. 비밀번호는 이미 바뀌었으니 실패해도 로그만 남기고 성공으로 응답
  private void clearFailures(Long memberId, int tokenVersionBeforeChange) {
    try {
      failureStore.clear(memberId, tokenVersionBeforeChange);
    } catch (RuntimeException e) {
      log.warn("비밀번호 실패 횟수 초기화 실패 (새 인증 버전에는 적용되지 않음): memberId={}", memberId, e);
    }
  }
}
