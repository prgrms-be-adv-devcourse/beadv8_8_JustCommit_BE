package com.justcommit.backend.member.infrastructure.lock;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 회원 행 잠금 + 대기 제한 (#409). 로그인 · 소셜 로그인 · 재발급 · 폐기가 같은 회원 행 잠금으로 줄을 선다.
 * - 잠그기 직전에 같은 트랜잭션에 lock_timeout을 건다 (set_config(..., true) = SET LOCAL, 트랜잭션이 끝날 때까지 유지)
 * - 대기 제한을 넘으면 PostgreSQL이 55P03(lock_not_available)을 내고, 이것만 409 MEMBER_BUSY로 바꾼다.
 *   BusinessException은 런타임 예외라 트랜잭션은 롤백된다
 * - 잠금 한 번마다의 대기 제한이다. 더 짧은 statement_timeout이 있으면 쿼리가 먼저 끝날 수 있다
 * - 배송지 변경 · 판매자 등록은 아직 MemberRepository 잠금을 직접 쓴다 (실패 응답이 바뀌므로 별도 작업)
 */
@Slf4j
@Component
public class MemberLocker {

  static final String LOCK_NOT_AVAILABLE = "55P03";
  private static final String SET_LOCK_TIMEOUT_SQL = "select set_config('lock_timeout', ?, true)";

  private final MemberRepository memberRepository;
  private final JdbcTemplate jdbcTemplate;
  private final Duration lockTimeout;

  public MemberLocker(MemberRepository memberRepository, JdbcTemplate jdbcTemplate,
                      @Value("${member.lock.timeout:3s}") Duration lockTimeout) {
    if (lockTimeout.isZero() || lockTimeout.isNegative()) {
      // 0은 PostgreSQL에서 "제한 없음"이라 대기 제한이 사라지므로 막는다
      throw new IllegalArgumentException("member.lock.timeout은 0보다 커야 합니다: " + lockTimeout);
    }
    this.memberRepository = memberRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.lockTimeout = lockTimeout;
  }

  public Optional<Member> lockById(Long memberId) {
    return lock(() -> memberRepository.findByIdForUpdate(memberId));
  }

  public Optional<Member> lockByEmail(String email) {
    return lock(() -> memberRepository.findByEmailForUpdate(email));
  }

  public Optional<Member> lockByProvider(Provider provider, String providerId) {
    return lock(() -> memberRepository.findByProviderAndProviderIdForUpdate(provider, providerId));
  }

  private Optional<Member> lock(Supplier<Optional<Member>> lockQuery) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      // 트랜잭션 밖이면 lock_timeout이 바로 사라지고 잠금도 유지되지 않는다
      throw new IllegalStateException("회원 잠금은 트랜잭션 안에서 해야 합니다.");
    }
    jdbcTemplate.queryForObject(SET_LOCK_TIMEOUT_SQL, String.class, lockTimeout.toMillis() + "ms");
    try {
      return lockQuery.get();
    } catch (RuntimeException e) {
      if (isLockNotAvailable(e)) {
        log.warn("회원 잠금 대기 제한 초과: timeout={}", lockTimeout);
        throw new BusinessException(MemberErrorCode.MEMBER_BUSY);
      }
      throw e;
    }
  }

  // 예외 원인을 따라가 SQLState 55P03(lock_not_available)인지 확인
  static boolean isLockNotAvailable(Throwable e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }
}
