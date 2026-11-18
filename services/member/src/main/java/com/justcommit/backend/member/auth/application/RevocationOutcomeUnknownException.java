package com.justcommit.backend.member.auth.application;

import lombok.Getter;

/**
 * 토큰 폐기 트랜잭션의 커밋 결과를 알 수 없음 (#409). 커밋 응답이 끊겨 DB 반영 여부를 모르는 경우.
 * 호출한 쪽은 변경(비밀번호 변경 · 탈퇴)을 자동으로 다시 실행하지 않는다:
 * 이미 반영됐다면 중복 실행이 되고, 반영됐다면 폐기 작업도 함께 저장돼 재처리 스케줄러가 Redis에 기록한다.
 */
@Getter
public class RevocationOutcomeUnknownException extends RuntimeException {

  private final Long memberId;

  public RevocationOutcomeUnknownException(Long memberId, Throwable cause) {
    super("토큰 폐기 커밋 결과를 확인할 수 없습니다: memberId=" + memberId, cause);
    this.memberId = memberId;
  }
}
