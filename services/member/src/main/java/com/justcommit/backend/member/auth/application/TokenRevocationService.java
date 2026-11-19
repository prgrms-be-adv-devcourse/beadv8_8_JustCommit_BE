package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.auth.domain.TokenRevocation;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import com.justcommit.backend.member.auth.infrastructure.repository.TokenRevocationRepository;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 비밀번호 변경 · 탈퇴 때 기존 토큰을 폐기한다 (#409). 실제 변경은 호출한 쪽(#411 · #407)이 change로 넘긴다.
 *
 * 1. 트랜잭션: 회원 잠금 → change → 인증 버전 +1 → 폐기 작업 저장(PENDING) → 커밋
 *    폐기 시각은 잠금 · 변경 뒤 DB 시각이라, 폐기 전에 발급된 Access의 iat는 항상 폐기 시각 이하다
 * 2. 커밋 뒤: Redis에 폐기 시각 기록 (TokenRevocationRecorder)
 * 3. 옛 인증 버전 Refresh 정리 1회 시도 (실패해도 재발급의 버전 비교가 막고 TTL로 만료)
 *
 * 결과: 변경 실패는 예외(롤백) / COMPLETED / PENDING(커밋됨, Redis 미확인) / 커밋 결과 불명은 RevocationOutcomeUnknownException
 * 반드시 트랜잭션 밖에서 호출한다: 안에서 부르면 커밋 시점을 이 서비스가 알 수 없다
 */
@Slf4j
@Service
public class TokenRevocationService {

  private final TransactionTemplate transactionTemplate;
  private final MemberLocker memberLocker;
  private final DatabaseClock databaseClock;
  private final TokenRevocationRepository revocationRepository;
  private final TokenRevocationRecorder revocationRecorder;
  private final RefreshTokenRedisStore refreshTokenRedisStore;
  // 폐기 키 보관 시간 = Access 수명 + 여유 (여유는 키 보관에만 쓰고 Gateway 비교에는 쓰지 않음)
  private final Duration retention;

  public TokenRevocationService(TransactionTemplate transactionTemplate, MemberLocker memberLocker,
                                DatabaseClock databaseClock, TokenRevocationRepository revocationRepository,
                                TokenRevocationRecorder revocationRecorder,
                                RefreshTokenRedisStore refreshTokenRedisStore,
                                @Value("${jwt.access-token-expiration}") Duration accessTokenExpiration,
                                @Value("${member.token-revocation.retention-margin:60s}") Duration retentionMargin) {
    if (retentionMargin.isNegative()) {
      throw new IllegalArgumentException("member.token-revocation.retention-margin은 0 이상이어야 합니다: " + retentionMargin);
    }
    this.transactionTemplate = transactionTemplate;
    this.memberLocker = memberLocker;
    this.databaseClock = databaseClock;
    this.revocationRepository = revocationRepository;
    this.revocationRecorder = revocationRecorder;
    this.refreshTokenRedisStore = refreshTokenRedisStore;
    this.retention = accessTokenExpiration.plus(retentionMargin);
  }

  // 폐기 콜백(change) 안에서만 쓴다: 이번 폐기가 커밋되면 회원이 갖게 될 인증 버전 (#422)
  // revoke()는 콜백 실행 → 인증 버전 +1 순서라, 콜백 안의 현재 버전 + 1이 폐기 후 버전이다
  // 버전을 예약하거나 바꾸지 않는 계산값이며, 회원 잠금이 잡힌 폐기 콜백 밖에서는 의미가 없다
  public static int versionAfterRevocation(Member member) {
    return member.getTokenVersion() + 1;
  }

  public RevocationResult revoke(Long memberId, RevocationReason reason, Consumer<Member> change) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("토큰 폐기는 트랜잭션 밖에서 호출해야 합니다.");
    }
    TokenRevocation revocation = commitRevocation(memberId, reason, change);
    boolean recorded = revocationRecorder.record(revocation);
    deleteOldRefreshTokens(revocation);
    return recorded ? RevocationResult.COMPLETED : RevocationResult.PENDING;
  }

  private TokenRevocation commitRevocation(Long memberId, RevocationReason reason, Consumer<Member> change) {
    // 마지막 쓰기를 saveAndFlush로 커밋 전에 DB에 보낸다 → 그 뒤의 예외는 커밋 단계 문제
    AtomicBoolean flushed = new AtomicBoolean(false);
    try {
      return transactionTemplate.execute(status -> {
        Member member = memberLocker.lockById(memberId)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
        change.accept(member);
        member.increaseTokenVersion();
        TokenRevocation saved = revocationRepository.saveAndFlush(TokenRevocation.create(
            memberId, reason, member.getTokenVersion(), databaseClock.now(), retention));
        flushed.set(true);
        return saved;
      });
    } catch (UnexpectedRollbackException e) {
      // 롤백이 확인됨 → 변경 실패
      throw e;
    } catch (RuntimeException e) {
      if (flushed.get()) {
        log.error("토큰 폐기 커밋 결과 불명: memberId={}", memberId, e);
        throw new RevocationOutcomeUnknownException(memberId, e);
      }
      throw e;
    }
  }

  private void deleteOldRefreshTokens(TokenRevocation revocation) {
    try {
      refreshTokenRedisStore.deleteOlderVersions(revocation.getMemberId(), revocation.getTokenVersion());
    } catch (RuntimeException e) {
      log.warn("옛 Refresh 정리 실패 (TTL로 만료): memberId={}", revocation.getMemberId(), e);
    }
  }
}
