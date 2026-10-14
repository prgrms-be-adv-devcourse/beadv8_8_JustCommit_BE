package com.justcommit.backend.member.application;

import com.justcommit.backend.member.MemberRegisteredEvent;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.SystemAccount;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

// 앱 시작 시 시스템 계정(홀딩·매출)이 없으면 생성 (있으면 건너뜀 → 재시작해도 중복 생성 안 됨)
// 로컬·CI·운영 어디서든 같은 방식으로 생성되도록 SQL 대신 코드로 처리
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemAccountInitializer implements ApplicationRunner {

  // 계좌 컬럼 임시값
  static final String BANK_CODE = "000";
  static final String ACCOUNT_NO = "0000000000";
  static final String ACCOUNT_HOLDER = "리플랜트";

  private final MemberRepository memberRepository;
  private final AccountCipher accountCipher;
  private final TransactionTemplate transactionTemplate;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  public void run(ApplicationArguments args) {
    for (SystemAccount account : SystemAccount.values()) {
      createIfMissing(account);
    }
  }

  // 확인·저장·이벤트 발행을 한 트랜잭션으로 묶음 (커밋 후 구독 쪽에서 지갑 생성)
  void createIfMissing(SystemAccount account) {
    try {
      Boolean created = transactionTemplate.execute(status -> {
        if (memberRepository.existsByEmail(account.email())) {
          return false;
        }
        Member member = memberRepository.saveAndFlush(Member.createSystem(
                account.email(),
                account.nickname(),
                account.phone(),
                BANK_CODE,
                accountCipher.encrypt(ACCOUNT_NO),
                ACCOUNT_HOLDER
        ));
        eventPublisher.publishEvent(new MemberRegisteredEvent(member.getId(), true));
        return true;
      });
      if (Boolean.TRUE.equals(created)) {
        log.info("시스템 계정 생성: {}", account);
      }
    } catch (DataIntegrityViolationException e) {
      // 앱 시작은 막지 않음. 기존 DB의 role 제약에 SYSTEM이 없으면 여기서 실패 (트랜잭션은 롤백 → 이벤트도 전달 안 됨)
      log.error("시스템 계정 생성 실패: {} (member_role_check 제약에 SYSTEM이 있는지 확인)", account, e);
    }
  }
}
