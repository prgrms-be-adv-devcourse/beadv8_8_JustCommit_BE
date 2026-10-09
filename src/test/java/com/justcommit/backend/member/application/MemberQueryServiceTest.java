package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberAccount;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MemberQueryServiceTest {

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private AccountCipher accountCipher;

  @InjectMocks
  private MemberQueryService memberQueryService;

  private static final String ACCOUNT_NO = "12345678901234";

  @Test
  @DisplayName("계좌 조회: 암호문을 복호화해 은행 코드·계좌번호·예금주를 반환")
  void getAccount_success() {
    Member member = Member.createLocal("test@gmail.com", "encoded", "떡볶이", "01012345678",
            "004", "v1:encrypted-account", "홍길동");
    given(memberRepository.findById(1L)).willReturn(Optional.of(member));
    given(accountCipher.decrypt("v1:encrypted-account")).willReturn(ACCOUNT_NO);

    MemberAccount account = memberQueryService.getAccount(1L);

    assertThat(account.bankCode()).isEqualTo("004");
    assertThat(account.accountNo()).isEqualTo(ACCOUNT_NO);
    assertThat(account.accountHolder()).isEqualTo("홍길동");
  }

  @Test
  @DisplayName("없는 회원이면 MEMBER_NOT_FOUND")
  void getAccount_memberNotFound() {
    given(memberRepository.findById(999L)).willReturn(Optional.empty());

    assertThatThrownBy(() -> memberQueryService.getAccount(999L))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
  }

  @Test
  @DisplayName("MemberAccount를 문자열로 출력하면 계좌번호는 뒤 4자리만 보임")
  void memberAccount_toStringMasked() {
    MemberAccount account = new MemberAccount("004", ACCOUNT_NO, "홍길동");

    assertThat(account.toString())
            .doesNotContain(ACCOUNT_NO)
            .contains("****1234");
  }
}
