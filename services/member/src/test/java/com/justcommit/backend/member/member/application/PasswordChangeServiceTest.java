package com.justcommit.backend.member.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.application.RevocationOutcomeUnknownException;
import com.justcommit.backend.member.auth.application.TokenRevocationService;
import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

// 비밀번호 변경의 커밋 결과 불명 처리 (#411). 실제 커밋 실패는 재현하기 어려워 폐기 서비스를 mock으로 둔다
@ExtendWith(MockitoExtension.class)
class PasswordChangeServiceTest {

  @Mock
  private TokenRevocationService tokenRevocationService;
  @Mock
  private PasswordEncoder passwordEncoder;

  @InjectMocks
  private PasswordChangeService passwordChangeService;

  @Test
  @DisplayName("커밋 결과 불명이면 503 RESULT_UNKNOWN, 새 비밀번호로 로그인해 확인하라는 메시지")
  void outcomeUnknownBecomes503() {
    given(tokenRevocationService.revoke(eq(4L), eq(RevocationReason.PASSWORD_CHANGED), any()))
        .willThrow(new RevocationOutcomeUnknownException(4L, new TransactionSystemException("커밋 응답 끊김")));

    assertThatThrownBy(() -> passwordChangeService.changePassword(4L, "plant1234", "green5678"))
        .isInstanceOf(BusinessException.class)
        .hasMessage("비밀번호 변경 결과를 확인하지 못했습니다. 새 비밀번호로 로그인해 확인해 주세요.")
        .satisfies(e -> {
          BusinessException be = (BusinessException) e;
          assertThat(be.getErrorCode()).isEqualTo(MemberErrorCode.RESULT_UNKNOWN);
          assertThat(be.getErrorCode().getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        });
  }
}
