package com.justcommit.backend.member.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class MemberTest {

  @Test
  @DisplayName("이메일 가입 회원의 인증 버전은 0으로 시작한다")
  void localMemberStartsAtZero() {
    Member member = Member.createLocal("test@gmail.com", "encoded", "떡볶이", "01012345678",
        "004", "v1:encrypted-account", null, "홍길동");

    assertThat(member.getTokenVersion()).isZero();
  }

  @Test
  @DisplayName("소셜 가입 회원의 인증 버전은 0으로 시작한다")
  void socialMemberStartsAtZero() {
    Member member = Member.createSocial(Provider.KAKAO, "1234567890", "kakao@gmail.com",
        "카카오", "01012345679", "004", "v1:encrypted-account", null, "홍길동");

    assertThat(member.getTokenVersion()).isZero();
  }

  @Test
  @DisplayName("인증 버전을 올리면 1씩 증가한다")
  void increaseTokenVersion() {
    Member member = Member.createLocal("test@gmail.com", "encoded", "떡볶이", "01012345678",
        "004", "v1:encrypted-account", null, "홍길동");

    member.increaseTokenVersion();
    assertThat(member.getTokenVersion()).isEqualTo(1);

    member.increaseTokenVersion();
    assertThat(member.getTokenVersion()).isEqualTo(2);
  }

  @Test
  @DisplayName("계좌를 바꾸면 은행 · 암호문 · HMAC · 예금주를 함께 바꾸고 계좌 변경 시각을 갱신한다 (#413)")
  void changeAccount() {
    Member member = Member.createLocal("test@gmail.com", "encoded", "떡볶이", "01012345678",
        "004", "v1:old-account", "old-hash", "홍길동");
    LocalDateTime before = member.getAccountUpdatedAt();

    member.changeAccount("088", "v1:new-account", "new-hash", "김철수");

    assertThat(member.getBankCode()).isEqualTo("088");
    assertThat(member.getAccountNoEnc()).isEqualTo("v1:new-account");
    assertThat(member.getAccountHash()).isEqualTo("new-hash");
    assertThat(member.getAccountHolder()).isEqualTo("김철수");
    assertThat(member.getAccountUpdatedAt()).isAfterOrEqualTo(before);
  }
}
