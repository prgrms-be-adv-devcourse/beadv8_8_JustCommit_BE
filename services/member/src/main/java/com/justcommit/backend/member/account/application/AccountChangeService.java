package com.justcommit.backend.member.account.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.account.infrastructure.AccountChangeTokenStore;
import com.justcommit.backend.member.auth.application.EmailSendResult;
import com.justcommit.backend.member.auth.application.EmailVerificationService;
import com.justcommit.backend.member.auth.domain.VerificationBinding;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.crypto.MemberHmac;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 계좌 변경 (#413). 로그인한 회원이 이메일 재인증으로 일회용 계좌 변경 토큰을 받아 환급 계좌를 바꾼다
 * - 인증번호는 회원 정보의 이메일로 보내고, 발송 당시 회원 id · 인증 버전을 번호에 묶는다
 * - 인증번호가 맞으면 묶어 둔 회원 id · 인증 버전을 지금 회원과 비교한다.
 *   다르거나(인증번호를 받은 뒤 비밀번호 변경 등) 활동 중이 아니면 토큰을 발급하지 않는다
 * - 토큰: 32바이트 난수(Base64url). Redis에 해시로, 발송 당시 회원 id · 인증 버전과 함께 10분 저장
 * - 계좌 변경: 토큰 확인(지우지 않음) → 회원 행 잠금 · 인증 버전 확인 → 다른 회원과 같은 계좌인지 사전 조회
 *   → 토큰 꺼내기(GETDEL) → 저장. 형식 오류 · 토큰 오류 · 잠금 대기 초과 · 중복 사전 조회까지는 토큰이 남고,
 *   꺼낸 뒤의 실패(동시 중복의 DB UNIQUE 위반 · 저장 실패)는 토큰이 소모된다 (이메일 인증부터 다시)
 * - 외부 계좌 인증은 하지 않는다 (계좌 실존 · 예금주 일치 · 소유 여부는 확인하지 않음). MemberUpdated 발행 없음
 */
@Service
@RequiredArgsConstructor
public class AccountChangeService {

  static final Duration CHANGE_TOKEN_TTL = Duration.ofMinutes(10);
  private static final int CHANGE_TOKEN_BYTES = 32; // 256비트 랜덤
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final MemberRepository memberRepository;
  private final EmailVerificationService emailVerificationService;
  private final AccountChangeTokenStore tokenStore;
  private final MemberLocker memberLocker;
  private final MemberHmac memberHmac;
  private final AccountCipher accountCipher;

  // 계좌 변경 인증번호 발송: 활동 중인 회원만 (아니면 404 MEMBER_NOT_FOUND)
  public EmailSendResult sendVerificationCode(Long memberId) {
    Member member = memberRepository.findById(memberId)
        .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    VerificationBinding binding = new VerificationBinding(member.getId(), member.getTokenVersion());
    return emailVerificationService.sendAccountChangeCode(member.getEmail(), binding);
  }

  // 인증번호 확인 → 계좌 변경 토큰 발급
  // 번호가 틀림 · 만료 · 오답 한도면 각 오류, 번호는 맞지만 발송 당시와 회원 · 인증 버전 · 상태가 다르면 ACCOUNT_VERIFICATION_INVALID
  public AccountChangeTokenResult issueChangeToken(Long memberId, String code) {
    Member member = memberRepository.findById(memberId)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    VerificationBinding binding = emailVerificationService.verifyAccountChangeCode(member.getEmail(), code)
        .filter(sent -> stillValid(sent, member))
        .orElseThrow(() -> new BusinessException(MemberErrorCode.ACCOUNT_VERIFICATION_INVALID));
    String token = newToken();
    tokenStore.save(token, binding, CHANGE_TOKEN_TTL);
    return new AccountChangeTokenResult(token, CHANGE_TOKEN_TTL.toSeconds());
  }

  // 계좌 변경 (요청 형식은 컨트롤러에서 검증을 마친 값)
  @Transactional
  public void changeAccount(Long memberId, AccountChangeCommand command) {
    // 1. 토큰 확인 (지우지 않음): 없음 · 만료 · 다른 회원의 토큰이면 거절
    VerificationBinding owner = tokenStore.find(command.accountChangeToken())
        .filter(found -> found.memberId().equals(memberId))
        .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_ACCOUNT_CHANGE_TOKEN));

    // 2. 회원 행 잠금 (대기 제한을 넘으면 409 MEMBER_BUSY). 토큰을 받은 뒤 인증 버전이 바뀌었거나 활동 중이 아니면 거절
    Member member = memberLocker.lockById(memberId)
        .filter(m -> stillValid(owner, m))
        .orElseThrow(() -> new BusinessException(MemberErrorCode.INVALID_ACCOUNT_CHANGE_TOKEN));

    // 3. 다른 회원이 쓰는 계좌면 거절 (토큰은 남겨 다른 계좌로 다시 시도할 수 있게)
    String accountHash = memberHmac.accountHash(command.bankCode(), command.accountNo());
    if (memberRepository.existsByAccountHashAndIdNot(accountHash, memberId)) {
      throw new BusinessException(MemberErrorCode.DUPLICATE_ACCOUNT);
    }

    // 4. 토큰을 꺼내며 지운다. 그 사이 같은 토큰을 다른 요청이 썼다면 거절 (같은 토큰으로는 한 번만 변경)
    boolean taken = tokenStore.take(command.accountChangeToken()).map(owner::equals).orElse(false);
    if (!taken) {
      throw new BusinessException(MemberErrorCode.INVALID_ACCOUNT_CHANGE_TOKEN);
    }

    // 5. 은행 · 계좌번호 · 예금주가 모두 같으면 바꾸지 않는다 (토큰은 소모, 변경 시각도 그대로)
    if (accountHash.equals(member.getAccountHash()) && command.accountHolder().equals(member.getAccountHolder())) {
      return;
    }
    member.changeAccount(command.bankCode(), accountCipher.encrypt(command.accountNo()), accountHash,
        command.accountHolder());
    // 동시에 다른 회원이 같은 계좌를 등록해 사전 조회를 통과한 경우: UNIQUE 위반이 커밋 때가 아니라 여기서 나도록 먼저 DB에 보냄
    try {
      memberRepository.flush();
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(toDuplicateErrorCode(e));
    }
  }

  // 발송(발급) 당시와 같은 회원 · 같은 인증 버전이고, 지금도 활동 중인지
  private static boolean stillValid(VerificationBinding sent, Member member) {
    return sent.memberId().equals(member.getId())
        && sent.tokenVersion() == member.getTokenVersion()
        && member.getStatus() == MemberStatus.ACTIVE;
  }

  // 계좌 제약만 409로 바꾸고, 예상하지 못한 제약 위반은 그대로 던짐 (500 + 로그)
  private static MemberErrorCode toDuplicateErrorCode(DataIntegrityViolationException e) {
    String message = String.valueOf(e.getMostSpecificCause().getMessage());
    if (message.contains("uk_member_account_hash")) {
      return MemberErrorCode.DUPLICATE_ACCOUNT;
    }
    throw e;
  }

  private static String newToken() {
    byte[] bytes = new byte[CHANGE_TOKEN_BYTES];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
