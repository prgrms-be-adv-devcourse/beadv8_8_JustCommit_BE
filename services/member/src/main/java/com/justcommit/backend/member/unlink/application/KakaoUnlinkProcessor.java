package com.justcommit.backend.member.unlink.application;

import com.justcommit.backend.member.auth.application.TokenRevocationService;
import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import com.justcommit.backend.member.unlink.domain.KakaoUnlinkReceipt;
import com.justcommit.backend.member.unlink.domain.KakaoUnlinkStatus;
import com.justcommit.backend.member.unlink.infrastructure.repository.KakaoUnlinkReceiptRepository;
import com.justcommit.backend.member.withdrawal.application.WithdrawalCheckResult;
import com.justcommit.backend.member.withdrawal.application.WithdrawalCheckService;
import com.justcommit.backend.member.withdrawal.application.WithdrawalService;
import com.justcommit.backend.member.withdrawal.infrastructure.kakao.KakaoAdminClient;
import com.justcommit.backend.member.withdrawal.infrastructure.kakao.KakaoApiUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 카카오 연결 해제 웹훅 처리 (#422). 처리 흐름 → docs/flows/signup-and-replica.md 「카카오 연결 해제 웹훅」
 * - 한 건씩: 짧은 트랜잭션에서 SKIP LOCKED로 확보(처리권 +1, 임대)하고 바로 커밋 → 카카오 · 내부 서비스 호출은 잠금 밖
 * - 결과 반영은 기록 행을 잠근 트랜잭션에서 자기 처리권(attemptNo)과 단계가 그대로일 때만
 * - 회원을 바꾸는 트랜잭션은 회원 잠금 → 기록 잠금 순서 (토큰 폐기 콜백이 회원을 먼저 잠근다)
 * - PENDING: 기록만 대상 거르기 → 카카오 연결 조회 → INACTIVE + 토큰 폐기
 * - INACTIVATED: 관리자 해제는 끝, 자동 탈퇴 사유는 자동 탈퇴 처리 기록을 정한 뒤 3곳 확인 → 탈퇴 또는 안내 대기
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoUnlinkProcessor {

  // 임대: 처리 중 앱이 죽어도 이 시간이 지나면 다른 실행이 다시 집는다 (결과 반영 조건은 아님)
  static final Duration LEASE = Duration.ofSeconds(60);
  // 한 주기에 처리할 최대 건수
  static final int MAX_PER_RUN = 50;

  static final String NOT_TARGET = "NOT_TARGET";
  static final String MEMBER_NOT_FOUND = "MEMBER_NOT_FOUND";
  static final String KAKAO_UNAVAILABLE = "KAKAO_UNAVAILABLE";
  static final String WITHDRAWAL_CHECK_UNAVAILABLE = "WITHDRAWAL_CHECK_UNAVAILABLE";
  static final String STATE_CHANGED = "STATE_CHANGED";
  static final String PROCESS_FAILED = "PROCESS_FAILED";

  private static final String INCOMPLETE_SIGN_UP = "INCOMPLETE_SIGN_UP";
  private static final String UNLINK_FROM_ADMIN = "UNLINK_FROM_ADMIN";
  // 자동 탈퇴 대상 사유 (관리자 해제는 사용자 의사를 알 수 없어 INACTIVE까지만)
  private static final Set<String> AUTO_WITHDRAWAL_TYPES =
      Set.of("ACCOUNT_DELETE", "FORCED_ACCOUNT_DELETE", "UNLINK_FROM_APPS");

  private final TransactionTemplate transactionTemplate;
  private final KakaoUnlinkReceiptRepository receiptRepository;
  private final MemberRepository memberRepository;
  private final MemberLocker memberLocker;
  private final KakaoAdminClient kakaoAdminClient;
  private final TokenRevocationService tokenRevocationService;
  private final WithdrawalCheckService withdrawalCheckService;
  private final WithdrawalService withdrawalService;
  private final DatabaseClock databaseClock;

  /**
   * @return 이번 주기에 확보해서 처리한 건수
   */
  public int processDue() {
    int processed = 0;
    while (processed < MAX_PER_RUN) {
      Optional<Claim> claim = claimNext();
      if (claim.isEmpty()) {
        break;
      }
      process(claim.get());
      processed++;
    }
    return processed;
  }

  // 확보한 시점의 처리권과 단계. 결과 반영은 이 번호와 단계가 그대로일 때만
  record Claim(Long id, int attemptNo, KakaoUnlinkStatus status, String providerId, String referrerType,
               Long memberId, Integer unlinkVersion) {
  }

  Optional<Claim> claimNext() {
    return transactionTemplate.execute(status -> receiptRepository.findNextWorkForUpdate()
        .map(receipt -> {
          receipt.claim(databaseClock.now().plus(LEASE));
          return new Claim(receipt.getId(), receipt.getAttemptNo(), receipt.getStatus(), receipt.getProviderId(),
              receipt.getReferrerType(), receipt.getMemberId(), receipt.getUnlinkVersion());
        }));
  }

  void process(Claim claim) {
    try {
      switch (claim.status()) {
        case PENDING -> processPending(claim);
        case INACTIVATED -> processInactivated(claim);
        default -> log.warn("카카오 연결 해제 웹훅 처리 대상이 아닌 단계: receiptId={}, status={}", claim.id(), claim.status());
      }
    } catch (RuntimeException e) {
      // 커밋 결과 불명이어도 다음 시도는 DB의 단계를 다시 읽고 판단한다. 처리권 · 단계가 그대로일 때만 실패를 남김
      log.warn("카카오 연결 해제 웹훅 처리 실패: receiptId={}, error={}", claim.id(), e.getClass().getSimpleName());
      reflect(claim, receipt -> receipt.recordFailure(PROCESS_FAILED, databaseClock.now()));
    }
  }

  // ===== PENDING: 기록만 대상 거르기 → 카카오 연결 조회 → INACTIVE + 토큰 폐기 =====

  private void processPending(Claim claim) {
    // 기록만 대상: 카카오 조회 없이 끝냄
    if (INCOMPLETE_SIGN_UP.equals(claim.referrerType())) {
      reflect(claim, receipt -> receipt.ignore(NOT_TARGET));
      return;
    }
    // 탈퇴하면 provider_id가 비워지므로, 이미 탈퇴한 회원도 여기서 "회원 없음"
    Optional<Member> member = memberRepository.findByProviderAndProviderId(Provider.KAKAO, claim.providerId())
        .filter(m -> m.getStatus() != MemberStatus.DELETED);
    if (member.isEmpty()) {
      reflect(claim, receipt -> receipt.ignore(MEMBER_NOT_FOUND));
      return;
    }

    KakaoAdminClient.LinkStatus link;
    try {
      link = kakaoAdminClient.linkStatus(claim.providerId());
    } catch (KakaoApiUnavailableException e) {
      reflect(claim, receipt -> receipt.recordFailure(KAKAO_UNAVAILABLE, databaseClock.now()));
      return;
    }
    if (link == KakaoAdminClient.LinkStatus.LINKED) {
      // 재연결 · 늦은 웹훅
      reflect(claim, KakaoUnlinkReceipt::invalidate);
      return;
    }
    inactivate(claim, member.get().getId());
  }

  // 끊김: 회원 잠금(토큰 폐기) 안에서 기록을 잠그고 처리권 · 회원 상태를 다시 확인한 뒤 INACTIVE + 폐기 후 버전 저장
  private void inactivate(Claim claim, Long memberId) {
    try {
      tokenRevocationService.revoke(memberId, RevocationReason.SOCIAL_UNLINKED, member -> {
        KakaoUnlinkReceipt receipt = lockIfClaimed(claim).orElseThrow(() -> Skip.NOT_CLAIMED);
        // 토큰 폐기는 콜백이 정상 종료하면 인증 버전을 올리므로, 폐기하지 않을 때는 예외로 롤백한다
        if (member.getStatus() != MemberStatus.ACTIVE) {
          throw Skip.STATE_CHANGED;
        }
        member.markSocialUnlinked();
        receipt.markInactivated(member.getId(), TokenRevocationService.versionAfterRevocation(member),
            databaseClock.now());
      });
    } catch (Skip skip) {
      if (skip == Skip.STATE_CHANGED) {
        attachOrIgnore(claim, memberId);
      }
      // NOT_CLAIMED: 다른 작업자가 가져갔거나 재로그인으로 무효 → 아무것도 하지 않음
    }
  }

  // 폐기 없이 처리: 롤백으로 잠금이 풀린 사이 상태가 바뀌었을 수 있어 회원 → 기록 잠금을 다시 잡고 재확인한다
  private void attachOrIgnore(Claim claim, Long memberId) {
    transactionTemplate.executeWithoutResult(status -> {
      Optional<Member> member = memberLocker.lockById(memberId);
      lockIfClaimed(claim).ifPresent(receipt -> {
        MemberStatus memberStatus = member.map(Member::getStatus).orElse(MemberStatus.DELETED);
        switch (memberStatus) {
          // 이미 이 연결 해제 상태로 폐기됨: 추가 폐기 없이 현재 버전에 연결 (예외 전에 읽은 버전을 쓰지 않음)
          case INACTIVE -> receipt.markInactivated(memberId, member.get().getTokenVersion(), databaseClock.now());
          // 그 사이 탈퇴
          case DELETED -> receipt.ignore(MEMBER_NOT_FOUND);
          // 그 사이 ACTIVE로 돌아옴: 처음부터 다시 판단 (재연결이면 다음 조회에서 연결됨으로 무효)
          case ACTIVE -> receipt.recordFailure(STATE_CHANGED, databaseClock.now());
        }
      });
    });
  }

  // ===== INACTIVATED: 관리자 해제는 끝, 자동 탈퇴 사유는 처리 기록을 정한 뒤 3곳 확인 → 탈퇴 또는 안내 대기 =====

  private void processInactivated(Claim claim) {
    if (!decideWithdrawalHandler(claim)) {
      return;
    }
    WithdrawalCheckResult check = withdrawalCheckService.check(claim.memberId());
    switch (check.status()) {
      case ALLOWED -> withdraw(claim);
      // 진행 중인 거래 등으로 지금은 탈퇴할 수 없음: INACTIVE로 두고 가입 이메일로 안내 (다음 단계)
      case BLOCKED -> reflect(claim, receipt -> receipt.markNoticePending(databaseClock.now()));
      // 확인 실패: 안내 없이 재시도, 한도를 넘으면 FAILED로 운영자 확인
      case UNAVAILABLE -> reflect(claim, receipt -> receipt.recordFailure(WITHDRAWAL_CHECK_UNAVAILABLE, databaseClock.now()));
    }
  }

  /**
   * 회원 잠금 → 기록 잠금 안에서 이 기록이 할 일을 정한다.
   * @return 3곳 확인으로 넘어가면 true (이 기록이 자동 탈퇴 처리 기록)
   */
  private boolean decideWithdrawalHandler(Claim claim) {
    Boolean proceed = transactionTemplate.execute(status -> {
      Optional<Member> member = memberLocker.lockById(claim.memberId());
      Optional<KakaoUnlinkReceipt> claimed = lockIfClaimed(claim);
      if (claimed.isEmpty()) {
        return false;
      }
      KakaoUnlinkReceipt receipt = claimed.get();
      // 이 연결 해제 상태가 이미 끝났으면(재로그인 복구 · 탈퇴 · 다시 연결 해제) 이 기록은 더 할 일이 없음
      boolean sameUnlinkState = member.isPresent()
          && member.get().getStatus() == MemberStatus.INACTIVE
          && member.get().getTokenVersion() == receipt.getUnlinkVersion();
      if (!sameUnlinkState) {
        receipt.invalidate();
        return false;
      }
      Optional<KakaoUnlinkReceipt> handler = receiptRepository
          .findByMemberIdAndUnlinkVersionAndHandlesWithdrawalTrue(receipt.getMemberId(), receipt.getUnlinkVersion());
      boolean handledByOther = handler.isPresent() && !handler.get().getId().equals(receipt.getId());
      if (UNLINK_FROM_ADMIN.equals(receipt.getReferrerType())) {
        if (handledByOther) {
          receipt.markDuplicate();
        } else {
          receipt.markDone();
        }
        return false;
      }
      if (!AUTO_WITHDRAWAL_TYPES.contains(receipt.getReferrerType())) {
        receipt.markDone();
        return false;
      }
      if (handledByOther) {
        receipt.markDuplicate();
        return false;
      }
      // 없으면 이 기록을 지정, 자신이면 이어서 수행 (재시도 · 운영자 재개)
      receipt.markHandlesWithdrawal();
      return true;
    });
    return Boolean.TRUE.equals(proceed);
  }

  // 최종 탈퇴: 회원 잠금 → 기록 잠금 안에서 INACTIVE · 버전 · 처리 기록 · 단계 · 처리권을 모두 확인한 뒤에만
  private void withdraw(Claim claim) {
    try {
      withdrawalService.withdrawUnlinkedMember(claim.memberId(), member -> {
        KakaoUnlinkReceipt receipt = lockIfClaimed(claim).orElseThrow(() -> Skip.NOT_CLAIMED);
        boolean stillTarget = receipt.isHandlesWithdrawal()
            && member.getStatus() == MemberStatus.INACTIVE
            && member.getTokenVersion() == receipt.getUnlinkVersion();
        if (!stillTarget) {
          throw Skip.STATE_CHANGED;
        }
        receipt.markDone();
      });
    } catch (Skip skip) {
      if (skip == Skip.STATE_CHANGED) {
        // 확인 뒤 상태가 바뀜: 다음 시도에서 처음부터 다시 판단 (재로그인이면 이미 무효라 처리권 확인에서 걸림)
        reflect(claim, receipt -> receipt.recordFailure(STATE_CHANGED, databaseClock.now()));
      }
    }
  }

  // ===== 공통 =====

  // 기록 행을 잠그고 처리권 · 단계가 그대로일 때만 바꾼다. 0건이면 아무것도 하지 않음
  private void reflect(Claim claim, Consumer<KakaoUnlinkReceipt> change) {
    transactionTemplate.executeWithoutResult(status -> lockIfClaimed(claim).ifPresent(change));
  }

  private Optional<KakaoUnlinkReceipt> lockIfClaimed(Claim claim) {
    return receiptRepository.findByIdForUpdate(claim.id())
        .filter(receipt -> receipt.isClaimedBy(claim.attemptNo(), claim.status()));
  }

  // 회원 변경을 하지 않고 롤백시키는 신호 (스택 없이)
  private static final class Skip extends RuntimeException {
    static final Skip NOT_CLAIMED = new Skip();
    static final Skip STATE_CHANGED = new Skip();

    private Skip() {
      super(null, null, false, false);
    }
  }
}
