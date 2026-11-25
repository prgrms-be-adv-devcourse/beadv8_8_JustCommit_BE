package com.justcommit.backend.member.withdrawal.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.MemberHmac;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import com.justcommit.backend.member.withdrawal.infrastructure.repository.MemberWithdrawalRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

// 재가입 제한 (#407): 탈퇴 후 30일 동안 같은 이메일 · 휴대폰으로 가입할 수 없다
// 로컬 · 소셜 가입이 같은 판정을 쓰도록 여기 한 곳에 둔다 (docs/flows/signup-and-replica.md 「재가입 제한」)
@Service
@RequiredArgsConstructor
public class RejoinRestriction {

  public static final Duration PERIOD = Duration.ofDays(30);

  private final MemberWithdrawalRecordRepository recordRepository;
  private final MemberHmac memberHmac;
  private final DatabaseClock databaseClock;

  // 가입 트랜잭션 안에서, 회원을 INSERT(saveAndFlush)한 뒤에 부른다. 걸리면 예외로 가입 전체가 롤백된다
  // 판정은 DB 시각 기준이라, 기간이 지난 기록을 정리 스케줄러가 아직 지우지 않았어도 결과가 같다
  // 정확히 30일 전에 탈퇴했으면 허용한다 (withdrawnAt > since)
  public void check(String email, String phone) {
    Instant since = databaseClock.now().minus(PERIOD);
    if (recordRepository.existsWithdrawnSince(memberHmac.emailHash(email), memberHmac.phoneHash(phone), since)) {
      throw new BusinessException(MemberErrorCode.REJOIN_RESTRICTED);
    }
  }

  // 가입 앞 단계 안내 (#611): 가입 인증번호 발송 · 카카오 신규 가입 판정에서 이메일만 본다.
  // 화면 안내용이라 최종 판정은 위 check가 가입 트랜잭션 안에서 다시 한다 (앞 단계를 건너뛴 요청 · 탈퇴와 동시 가입)
  public void checkEmail(String email) {
    if (recordRepository.existsEmailWithdrawnSince(memberHmac.emailHash(email), since())) {
      throw new BusinessException(MemberErrorCode.REJOIN_RESTRICTED_EMAIL);
    }
  }

  // 가입 앞 단계 안내 (#611): 휴대폰 중복 확인에서 휴대폰만 본다. 최종 판정은 check
  public void checkPhone(String phone) {
    if (recordRepository.existsPhoneWithdrawnSince(memberHmac.phoneHash(phone), since())) {
      throw new BusinessException(MemberErrorCode.REJOIN_RESTRICTED_PHONE);
    }
  }

  private Instant since() {
    return databaseClock.now().minus(PERIOD);
  }
}
