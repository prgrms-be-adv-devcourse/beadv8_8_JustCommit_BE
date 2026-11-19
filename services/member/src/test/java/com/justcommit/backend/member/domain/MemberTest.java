package com.justcommit.backend.member.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  @Test
  @DisplayName("카카오 연결 해제는 ACTIVE에서 INACTIVE로, 복구는 INACTIVE에서 ACTIVE로만 바뀐다 (#422)")
  void socialUnlinkAndRestore() {
    Member member = activeMember();

    member.markSocialUnlinked();
    assertThat(member.getStatus()).isEqualTo(MemberStatus.INACTIVE);
    assertThatThrownBy(member::markSocialUnlinked).isInstanceOf(IllegalStateException.class);

    member.restoreActive();
    assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    assertThatThrownBy(member::restoreActive).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("탈퇴한 회원은 연결 해제 상태로도, 복구로도 바뀌지 않는다 (#422)")
  void deletedMemberCannotChange() {
    Member member = activeMember();
    member.withdraw();

    assertThatThrownBy(member::markSocialUnlinked).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(member::restoreActive).isInstanceOf(IllegalStateException.class);
    assertThat(member.getStatus()).isEqualTo(MemberStatus.DELETED);
  }

  private static Member activeMember() {
    return Member.createLocal("unlink@gmail.com", "encoded", "연결", "01012345678",
        "004", "v1:account", "hash", "홍길동");
  }
}
