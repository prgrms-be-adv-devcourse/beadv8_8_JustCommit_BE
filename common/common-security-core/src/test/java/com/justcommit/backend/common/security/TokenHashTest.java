package com.justcommit.backend.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenHashTest {

  @Test
  @DisplayName("SHA-256 소문자 16진수 64글자 (표준 시험값 \"abc\")")
  void sha256Hex() {
    assertThat(TokenHash.sha256Hex("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }
}
