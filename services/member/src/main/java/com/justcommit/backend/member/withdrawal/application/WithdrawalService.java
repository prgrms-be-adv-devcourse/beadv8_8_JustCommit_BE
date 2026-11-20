package com.justcommit.backend.member.withdrawal.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.auth.application.RevocationOutcomeUnknownException;
import com.justcommit.backend.member.auth.application.RevocationResult;
import com.justcommit.backend.member.auth.application.TokenRevocationService;
import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.auth.domain.VerificationBinding;
import com.justcommit.backend.member.auth.infrastructure.redis.PasswordFailureStore;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.domain.exception.PasswordChangeLockedException;
import com.justcommit.backend.member.event.MemberWithdrawn;
import com.justcommit.backend.member.infrastructure.crypto.MemberHmac;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import com.justcommit.backend.member.member.application.PasswordChangeService;
import com.justcommit.backend.member.seller.infrastructure.SellerRepository;
import com.justcommit.backend.member.withdrawal.domain.MemberWithdrawalRecord;
import com.justcommit.backend.member.withdrawal.infrastructure.kakao.KakaoAdminClient;
import com.justcommit.backend.member.withdrawal.infrastructure.redis.WithdrawalTokenStore;
import com.justcommit.backend.member.withdrawal.infrastructure.repository.MemberWithdrawalRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.function.Consumer;

/**
 * 직접 탈퇴 (#407). 흐름: docs/flows/signup-and-replica.md 「직접 탈퇴」, 결정: ADR 0028
 * - 트랜잭션을 열지 않는다. 외부 호출(3곳 확인 · 카카오 연결 해제) 동안 회원 잠금 · DB 트랜잭션을 잡지 않고,
 *   마지막 변경만 TokenRevocationService.revoke()의 회원 잠금 · 트랜잭션에서 한다 (revoke는 활성 트랜잭션 밖에서만 동작)
 * - 순서: 회원 확인 → 재확인 → 탈퇴 가능 여부 확인 → (카카오) 토큰 꺼내기 · 연결 해제 → 최종 처리
 * - 결과: 커밋됐으면 성공 (Redis 폐기 기록 확인 여부는 revocationCompleted로 따로 전달),
 *   커밋 결과 불명만 503 RESULT_UNKNOWN. 그 밖의 예외는 원래 오류 그대로 (최종 처리 중 예외면 전부 롤백)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {

  private static final String RESULT_UNKNOWN_MESSAGE =
      "탈퇴 결과를 확인하지 못했습니다. 잠시 후 다시 로그인해 확인해 주세요.";

  private final MemberRepository memberRepository;
  private final MemberLocker memberLocker;
  private final TransactionTemplate transactionTemplate;
  private final PasswordEncoder passwordEncoder;
  private final PasswordFailureStore failureStore;
  private final WithdrawalTokenStore tokenStore;
  private final WithdrawalCheckService withdrawalCheckService;
  private final KakaoAdminClient kakaoAdminClient;
  private final TokenRevocationService tokenRevocationService;
  private final MemberWithdrawalRecordRepository recordRepository;
  private final AddressRepository addressRepository;
  private final SellerRepository sellerRepository;
  private final MemberHmac memberHmac;
  private final DatabaseClock databaseClock;
  private final OutboxPublisher outboxPublisher;

  // 사전 확인 (화면 안내용, 결과를 저장하지 않음). 활동 중인 회원이 아니면 외부 확인 없이 404
  // 확인 실패(UNAVAILABLE)면 일부 서비스에서 받은 차단 사유도 보여 주지 않고 503
  public WithdrawalCheckResult check(Long memberId) {
    findActiveMember(memberId);
    WithdrawalCheckResult result = withdrawalCheckService.check(memberId);
    if (result.status() == WithdrawalCheckStatus.UNAVAILABLE) {
      throw new BusinessException(MemberErrorCode.WITHDRAWAL_CHECK_UNAVAILABLE);
    }
    return result;
  }

  // 최종 탈퇴. 로컬은 password, 카카오는 withdrawalToken 하나만 보낸다 (다르면 400)
  public WithdrawalResult withdraw(Long memberId, String password, String withdrawalToken) {
    Member member = findActiveMember(memberId);
    int verifiedVersion = switch (member.getProvider()) {
      case LOCAL -> reconfirmPassword(member, password, withdrawalToken);
      case KAKAO -> reconfirmToken(member, password, withdrawalToken);
      default -> throw new BusinessException(CommonErrorCode.BAD_REQUEST_DATA);
    };

    requireAllowed(withdrawalCheckService.check(memberId));

    boolean unlinked = false;
    if (member.getProvider() == Provider.KAKAO) {
      // 연결 해제 직전에 토큰을 꺼낸다(한 번만). 이 뒤의 실패는 토큰이 소모돼 이메일 인증부터 다시 해야 한다
      VerificationBinding verified = new VerificationBinding(memberId, verifiedVersion);
      boolean taken = tokenStore.take(withdrawalToken).map(verified::equals).orElse(false);
      if (!taken) {
        throw new BusinessException(MemberErrorCode.INVALID_WITHDRAWAL_TOKEN);
      }
      // 활동 중인 회원의 provider_id는 바뀌지 않으므로 처음에 읽은 값을 쓴다
      kakaoAdminClient.unlink(member.getProviderId());
      unlinked = true;
    }

    try {
      RevocationResult result = tokenRevocationService.revoke(memberId, RevocationReason.WITHDRAWN,
          locked -> anonymize(locked, verifiedVersion));
      return new WithdrawalResult(result == RevocationResult.COMPLETED);
    } catch (RevocationOutcomeUnknownException e) {
      // 카카오는 끊겼고 탈퇴 커밋 여부는 모름 (실제로 커밋됐을 수도 있어 커밋 안 됨과 다른 문구로 남김)
      if (unlinked) {
        log.warn("카카오 연결 해제 뒤 탈퇴 결과 불명: memberId={}", memberId);
      }
      throw new BusinessException(MemberErrorCode.RESULT_UNKNOWN, RESULT_UNKNOWN_MESSAGE);
    } catch (RuntimeException e) {
      // 카카오는 끊겼는데 이 요청의 탈퇴는 커밋되지 않음 (트랜잭션 시작 전 실패 포함). 탈퇴 토큰이 소모돼 이메일 재인증 후 다시 탈퇴해야 함
      // 카카오 회원번호 · 토큰 · 이메일은 남기지 않고 내부 회원 ID와 오류 종류 · 코드만. 예외는 그대로 전달
      if (unlinked) {
        log.warn("카카오 연결 해제 뒤 탈퇴 저장 실패(커밋 안 됨): memberId={}, error={}, code={}", memberId,
            e.getClass().getSimpleName(), e instanceof BusinessException be ? be.getErrorCode() : "-");
      }
      throw e;
    }
  }

  // 로컬 회원 재확인: 짧은 트랜잭션에서 회원 행을 잠그고 현재 비밀번호 확인 (비밀번호 변경과 실패 횟수 · 한도 공유)
  // 틀리면 잠금을 풀기 전에 실패를 기록한다 (Redis라 롤백과 무관하게 남음). 맞아도 탈퇴가 끝나기 전에는 횟수를 지우지 않는다
  // 확인한 인증 버전을 돌려주고 트랜잭션을 끝낸다 → 외부 호출 동안 잠금을 잡지 않음
  private int reconfirmPassword(Member member, String password, String withdrawalToken) {
    if (!StringUtils.hasText(password) || StringUtils.hasText(withdrawalToken)) {
      throw new BusinessException(CommonErrorCode.BAD_REQUEST_DATA);
    }
    Long memberId = member.getId();
    requireNotLocked(memberId, member.getTokenVersion()); // 이미 막혔으면 BCrypt 없이 429
    Integer version = transactionTemplate.execute(status -> {
      Member locked = memberLocker.lockById(memberId)
          .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
          .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
      // 잠근 회원의 버전으로 다시 확인: 잠금 전 확인 뒤 다른 요청이 오답을 기록했을 수 있다
      requireNotLocked(memberId, locked.getTokenVersion());
      if (!passwordEncoder.matches(password, locked.getPassword())) {
        failureStore.recordFailure(memberId, locked.getTokenVersion(), PasswordChangeService.FAILURE_WINDOW);
        throw new BusinessException(MemberErrorCode.INVALID_CURRENT_PASSWORD);
      }
      return locked.getTokenVersion();
    });
    return version;
  }

  // 카카오 회원 재확인: 탈퇴 토큰을 지우지 않고 확인 (꺼내기는 연결 해제 직전)
  // 없음 · 만료 · 다른 회원의 토큰 · 발급 뒤 인증 버전이 바뀐 토큰은 400
  private int reconfirmToken(Member member, String password, String withdrawalToken) {
    if (!StringUtils.hasText(withdrawalToken) || StringUtils.hasText(password)) {
      throw new BusinessException(CommonErrorCode.BAD_REQUEST_DATA);
    }
    VerificationBinding current = new VerificationBinding(member.getId(), member.getTokenVersion());
    return tokenStore.find(withdrawalToken)
        .filter(current::equals)
        .map(VerificationBinding::tokenVersion)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_WITHDRAWAL_TOKEN));
  }

  // 최종 처리: revoke()의 회원 잠금 · 트랜잭션 안에서 실행. 여기서 던진 예외는 그대로 전달되고 전부 롤백된다
  // 직접 탈퇴의 조건(ACTIVE · 재확인한 인증 버전)을 확인한 뒤 공통 최종 처리(removePersonalData)
  private void anonymize(Member member, int verifiedVersion) {
    // 그사이 다른 요청 · 웹훅이 먼저 탈퇴시켰거나 연결이 끊겼으면 중복 처리하지 않는다
    if (member.getStatus() != MemberStatus.ACTIVE) {
      throw new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND);
    }
    // 재확인 뒤 비밀번호 변경 · 재설정 등으로 인증 버전이 바뀌어 재확인이 무효가 된 경우 (오답이 아니라 실패 횟수에 넣지 않음)
    if (member.getTokenVersion() != verifiedVersion) {
      throw new BusinessException(member.getProvider() == Provider.LOCAL
          ? MemberErrorCode.INVALID_CURRENT_PASSWORD
          : MemberErrorCode.INVALID_WITHDRAWAL_TOKEN);
    }
    removePersonalData(member);
  }

  /**
   * 카카오 연결 해제 웹훅의 자동 탈퇴 (#422). 재확인 · 카카오 연결 해제 없이 최종 처리만 하며, 이 경로에서만 INACTIVE 회원의 탈퇴를 허용한다.
   * check는 같은 회원 잠금 · 트랜잭션 안에서 먼저 실행된다(수신 기록 확인 · 갱신). 예외를 던지면 전부 롤백
   */
  public RevocationResult withdrawUnlinkedMember(Long memberId, Consumer<Member> check) {
    return tokenRevocationService.revoke(memberId, RevocationReason.WITHDRAWN, member -> {
      check.accept(member);
      if (member.getStatus() != MemberStatus.INACTIVE) {
        throw new IllegalStateException("카카오 연결 해제 자동 탈퇴는 INACTIVE 회원만 가능: " + member.getStatus());
      }
      removePersonalData(member);
    });
  }

  // 탈퇴 공통 최종 처리 (직접 탈퇴 · 웹훅 자동 탈퇴): 탈퇴 기록 · 배송지 · 소개글 · 익명화 · MemberWithdrawn
  private void removePersonalData(Member member) {
    // 탈퇴 기록은 익명화 전 이메일 · 휴대폰으로 (재가입 제한, DB 시각)
    recordRepository.save(MemberWithdrawalRecord.create(
        memberHmac.emailHash(member.getEmail()), memberHmac.phoneHash(member.getPhone()), databaseClock.now()));
    // 배송지 · 소개글은 각 서비스(배송지 변경 이벤트 등)를 거치지 않고 직접 지운다. 다른 서비스에는 MemberWithdrawn 하나로 알린다
    addressRepository.deleteAllByMemberId(member.getId());
    sellerRepository.findById(member.getId()).ifPresent(seller -> seller.changeIntro(null));
    member.withdraw();
    // 다른 서비스 복제본에 탈퇴를 알린다 (#408). 같은 트랜잭션이라 탈퇴가 롤백되면 이벤트도 남지 않음
    outboxPublisher.publish(new MemberWithdrawn(member.getId()));
  }

  private Member findActiveMember(Long memberId) {
    return memberRepository.findById(memberId)
        .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
  }

  private static void requireAllowed(WithdrawalCheckResult result) {
    switch (result.status()) {
      case ALLOWED -> {
      }
      case BLOCKED -> throw new BusinessException(MemberErrorCode.WITHDRAWAL_BLOCKED);
      case UNAVAILABLE -> throw new BusinessException(MemberErrorCode.WITHDRAWAL_CHECK_UNAVAILABLE);
    }
  }

  // 같은 인증 버전에서 한도만큼 틀렸으면 429 + 남은 초 (비밀번호 변경과 같은 한도)
  private void requireNotLocked(Long memberId, int tokenVersion) {
    long retryAfterSeconds = failureStore.retryAfterSeconds(
        memberId, tokenVersion, PasswordChangeService.MAX_CURRENT_PASSWORD_FAILURES);
    if (retryAfterSeconds > 0) {
      throw new PasswordChangeLockedException(retryAfterSeconds);
    }
  }
}
