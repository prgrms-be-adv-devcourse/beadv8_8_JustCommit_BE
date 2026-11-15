package com.justcommit.backend.member.auth.infrastructure.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenOwnerTest {

  @Test
  @DisplayName("저장 값은 \"{memberId}:{인증 버전}\"")
  void toValue() {
    assertThat(new RefreshTokenOwner(4L, 0).toValue()).isEqualTo("4:0");
    assertThat(new RefreshTokenOwner(123L, 7).toValue()).isEqualTo("123:7");
  }

  @Test
  @DisplayName("규칙에 맞는 값은 회원 id와 인증 버전으로 읽는다")
  void parseValid() {
    assertThat(RefreshTokenOwner.parse("4:0")).isEqualTo(Optional.of(new RefreshTokenOwner(4L, 0)));
    assertThat(RefreshTokenOwner.parse("123:7")).isEqualTo(Optional.of(new RefreshTokenOwner(123L, 7)));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"4", "4:", ":0", "4:0:", ":", "a:b", "4:x", "0:0", "4:-1", "-4:0", "+4:0", "4:+1",
      " 4:0", "4:0 ", "99999999999999999999:0", "4:99999999999"})
  @DisplayName("옛 형식 · 잘못된 형식 · 범위를 넘는 숫자는 예외 없이 없음")
  void parseInvalid(String value) {
    assertThat(RefreshTokenOwner.parse(value)).isEmpty();
  }

  @Test
  @DisplayName("memberId가 양수가 아니거나 인증 버전이 음수면 만들 수 없다")
  void rejectsInvalidValues() {
    assertThatThrownBy(() -> new RefreshTokenOwner(0L, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RefreshTokenOwner(4L, -1)).isInstanceOf(IllegalArgumentException.class);
  }
}
