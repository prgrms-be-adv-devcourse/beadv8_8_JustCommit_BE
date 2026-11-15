package com.justcommit.backend.member.member.presentation.internal;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.member.MemberAccount;
import com.justcommit.backend.member.application.MemberQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 서비스 간 내부 API. Gateway를 통한 접근은 제공하지 않고 직접 접근은 네트워크 설정과 내부 키 필터(X-Internal-Key)로 제한
// 규칙 -> docs/reference/internal-apis.md
@RestController
@RequestMapping("/internal/v1/members")
@RequiredArgsConstructor
public class MemberInternalController {

  private final MemberQueryService memberQueryService;

  // payment가 인출 신청 때 부르는 환급 계좌 조회 (#388)
  // 응답에는 복호화한 계좌번호 원문이 담기므로 응답 본문을 로그로 남기지 않는다
  // 없는 회원 · 탈퇴 회원은 404 MEMBER_NOT_FOUND
  @GetMapping("/{memberId}/account")
  public ApiResponse<MemberAccount> getAccount(@PathVariable Long memberId) {
    return ApiResponse.ok("계좌를 조회했습니다.", memberQueryService.getAccount(memberId));
  }
}
