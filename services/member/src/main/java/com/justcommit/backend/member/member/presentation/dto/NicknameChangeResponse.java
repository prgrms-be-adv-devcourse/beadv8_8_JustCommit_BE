package com.justcommit.backend.member.member.presentation.dto;

import com.justcommit.backend.member.member.application.NicknameChangeResult;

// 닉네임 변경 응답
public record NicknameChangeResponse(
    Long memberId,
    String nickname
) {
  public static NicknameChangeResponse from(NicknameChangeResult result) {
    return new NicknameChangeResponse(result.memberId(), result.nickname());
  }
}
