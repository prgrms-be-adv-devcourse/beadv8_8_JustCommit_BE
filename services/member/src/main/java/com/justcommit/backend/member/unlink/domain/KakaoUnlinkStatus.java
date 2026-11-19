package com.justcommit.backend.member.unlink.domain;

/**
 * 카카오 연결 해제 웹훅 수신 기록의 단계 (#422). 처리 흐름 → docs/flows/signup-and-replica.md
 */
public enum KakaoUnlinkStatus {
  PENDING,        // 받음. 카카오 연결 조회 전
  INACTIVATED,    // INACTIVE + 토큰 폐기 반영됨 (자동 탈퇴 판단 대기)
  NOTICE_PENDING, // 탈퇴 조건 BLOCKED, 안내 메일 대기
  DONE,
  IGNORED,        // 기록만 (잘못된 요청 · 처리 대상 아님)
  INVALIDATED,    // 재연결 · 재로그인으로 무효
  DUPLICATE,      // 같은 연결 해제 상태를 다른 기록이 처리
  FAILED          // 재시도를 다 써서 멈춤 (resume_state에서 운영자 재개)
}
