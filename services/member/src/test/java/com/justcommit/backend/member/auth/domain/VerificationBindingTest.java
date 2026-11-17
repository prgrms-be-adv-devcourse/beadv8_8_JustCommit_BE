package com.justcommit.backend.member.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationBindingTest {

  @Test
  @DisplayName("묶음 정보는 \"{회원 id}:{인증 버전}\"으로 저장하고, 비었거나 깨졌으면 빈 값")
  void binding() {
    assertThat(new VerificationBinding(7L, 3).toBinding()).isEqualTo("7:3");
    assertThat(VerificationBinding.fromBinding("7:3")).contains(new VerificationBinding(7L, 3));
    assertThat(VerificationBinding.fromBinding("")).isEmpty();
    assertThat(VerificationBinding.fromBinding(null)).isEmpty();
    assertThat(VerificationBinding.fromBinding("broken")).isEmpty();
    assertThat(VerificationBinding.fromBinding("x:3")).isEmpty();
    assertThat(VerificationBinding.fromBinding(":3")).isEmpty();
  }
}
