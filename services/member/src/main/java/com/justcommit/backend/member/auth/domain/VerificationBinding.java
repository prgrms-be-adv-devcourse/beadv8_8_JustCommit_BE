package com.justcommit.backend.member.auth.domain;

import java.util.Optional;

// 인증번호에 묶어 두는 회원 정보: 인증번호를 보낼 때의 회원 id · 인증 버전
// 인증번호와 함께 저장했다가 확인에 성공하면 그대로 돌려받아, 그 사이 회원이나 인증 상태가 바뀌었는지 비교한다
// (비밀번호 재설정 #412 · 계좌 변경 #413이 함께 쓴다)
public record VerificationBinding(Long memberId, int tokenVersion) {

  // 인증번호에 함께 저장하는 형식 "{회원 id}:{인증 버전}"
  public String toBinding() {
    return memberId + ":" + tokenVersion;
  }

  // 저장해 둔 값을 되읽는다. 비었거나 깨졌으면 빈 값 (이때는 토큰을 발급하지 않는다)
  public static Optional<VerificationBinding> fromBinding(String binding) {
    if (binding == null) {
      return Optional.empty();
    }
    int separator = binding.indexOf(':');
    if (separator <= 0) {
      return Optional.empty();
    }
    try {
      return Optional.of(new VerificationBinding(Long.parseLong(binding.substring(0, separator)),
          Integer.parseInt(binding.substring(separator + 1))));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
