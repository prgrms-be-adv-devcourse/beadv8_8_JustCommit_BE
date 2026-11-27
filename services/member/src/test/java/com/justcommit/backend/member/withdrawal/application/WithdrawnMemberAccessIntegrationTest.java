package com.justcommit.backend.member.withdrawal.application;

import com.justcommit.backend.member.support.MemberIntegrationTest;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.address.application.AddressCreateCommand;
import com.justcommit.backend.member.address.application.AddressService;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.member.application.MyInfoService;
import com.justcommit.backend.member.member.application.NicknameChangeService;
import com.justcommit.backend.member.seller.application.SellerProfileService;
import com.justcommit.backend.member.seller.application.SellerRegisterCommand;
import com.justcommit.backend.member.seller.application.SellerRegisterService;
import com.justcommit.backend.member.seller.domain.SellerReviewConsentPolicy;
import com.justcommit.backend.member.seller.infrastructure.SellerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [S-A11] 탈퇴 회원 차단 (#407, DELETED 범위).
 * 토큰 폐기 기록이 PENDING이면 기존 Access가 Gateway를 지나올 수 있으므로, member 서비스가 회원 상태로 한 번 더 막는다.
 * 탈퇴 직후 모습(DELETED + 계좌 NULL)을 만들어, 복호화 오류(500)가 아니라 404로 끝나고 아무것도 저장되지 않는지 확인한다.
 * 카카오 연결 해제(INACTIVE, #422)도 같은 404로 막는다. INACTIVE는 계좌 · 개인정보가 남아 있어, 막지 않으면 조회 · 변경이 그대로 성공한다.
 * (정산 · 환급용 내부 계좌 조회는 INACTIVE에도 응답하므로 여기서 다루지 않음)
 */
class WithdrawnMemberAccessIntegrationTest extends MemberIntegrationTest {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired
  private MyInfoService myInfoService;

  @Autowired
  private AddressService addressService;

  @Autowired
  private SellerRegisterService sellerRegisterService;

  @Autowired
  private NicknameChangeService nicknameChangeService;

  @Autowired
  private SellerProfileService sellerProfileService;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private AddressRepository addressRepository;

  @Autowired
  private SellerRepository sellerRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("[S-A11] 탈퇴 회원의 내 정보 조회는 계좌 복호화 전에 404 MEMBER_NOT_FOUND")
  void myInfoOfWithdrawnMember() {
    Long memberId = withdrawnMemberId();

    assertThatThrownBy(() -> myInfoService.getMyInfo(memberId))
        .isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
  }

  @Test
  @DisplayName("[S-A11] 탈퇴 회원의 배송지 등록은 404 MEMBER_NOT_FOUND, 배송지가 저장되지 않음")
  void addAddressOfWithdrawnMember() {
    Long memberId = withdrawnMemberId();
    AddressCreateCommand command = new AddressCreateCommand(
        "홍길동", "01012345678", "12345", "서울시 어딘가 1", "101호", "집", true);

    assertThatThrownBy(() -> addressService.addAddress(memberId, command))
        .isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
    assertThat(addressRepository.countByMemberId(memberId)).isZero();
  }

  @Test
  @DisplayName("[S-A11] 탈퇴 회원의 판매자 등록은 404 MEMBER_NOT_FOUND, 판매자 행이 생기지 않음")
  void sellerRegisterOfWithdrawnMember() {
    Long memberId = withdrawnMemberId();
    SellerRegisterCommand command = new SellerRegisterCommand(memberId, "소개", SellerReviewConsentPolicy.VERSION);

    assertThatThrownBy(() -> sellerRegisterService.register(command))
        .isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
    assertThat(sellerRepository.existsById(memberId)).isFalse();
  }

  @Test
  @DisplayName("[S-A11] 카카오 연결 해제(INACTIVE) 회원의 내 정보 조회 · 닉네임 변경 · 소개글 수정 · 배송지 등록 · 판매자 등록은 404, 아무것도 바뀌지 않음 (#422)")
  void inactiveMemberIsBlocked() {
    Long memberId = inactiveMemberId();
    AddressCreateCommand address = new AddressCreateCommand(
        "홍길동", "01012345678", "12345", "서울시 어딘가 1", "101호", "집", true);

    assertNotFound(() -> myInfoService.getMyInfo(memberId));
    assertNotFound(() -> nicknameChangeService.changeNickname(memberId, "바뀐닉"));
    assertNotFound(() -> sellerProfileService.changeIntro(memberId, "바뀐 소개"));
    assertNotFound(() -> addressService.addAddress(memberId, address));
    assertNotFound(() -> sellerRegisterService.register(
        new SellerRegisterCommand(memberId, "소개", SellerReviewConsentPolicy.VERSION)));

    assertThat(memberRepository.findById(memberId).orElseThrow().getNickname()).startsWith("연결");
    assertThat(addressRepository.countByMemberId(memberId)).isZero();
    assertThat(sellerRepository.existsById(memberId)).isFalse();
  }

  @Test
  @DisplayName("[S-A11] 배송지 목록 조회: ACTIVE는 목록(없으면 빈 목록), INACTIVE(배송지가 남아 있어도) · 탈퇴 · 없는 회원은 404 (#422)")
  void addressListOnlyForActiveMember() {
    Long memberId = activeMemberId();
    assertThat(addressService.getAddresses(memberId)).isEmpty();
    addressService.addAddress(memberId, new AddressCreateCommand(
        "홍길동", "01012345678", "12345", "서울시 어딘가 1", "101호", "집", true));
    assertThat(addressService.getAddresses(memberId)).hasSize(1);

    // 카카오 연결 해제로 INACTIVE: 배송지는 남아 있지만 목록은 404
    jdbcTemplate.update("update member set status = 'INACTIVE' where id = ?", memberId);
    assertNotFound(() -> addressService.getAddresses(memberId));
    assertThat(addressRepository.countByMemberId(memberId)).isEqualTo(1);

    assertNotFound(() -> addressService.getAddresses(withdrawnMemberId()));
    assertNotFound(() -> addressService.getAddresses(Long.MAX_VALUE));
  }

  private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
  }

  private Long activeMemberId() {
    int n = SEQ.incrementAndGet();
    return memberRepository.save(Member.createLocal(
        "active" + n + "@test.com", "encoded-password", "활동" + n, String.format("015%08d", n),
        "004", "v1:test-account", null, "홍길동")).getId();
  }

  // 카카오 연결 해제 직후 모습: 상태 INACTIVE, 개인정보 · 계좌는 그대로
  private Long inactiveMemberId() {
    int n = SEQ.incrementAndGet();
    Member member = memberRepository.save(Member.createLocal(
        "inactive" + n + "@test.com", "encoded-password", "연결" + n, String.format("015%08d", n),
        "004", "v1:test-account", null, "홍길동"));
    jdbcTemplate.update("update member set status = 'INACTIVE' where id = ?", member.getId());
    return member.getId();
  }

  // 탈퇴 직후 모습: 상태 DELETED + 개인정보 · 계좌 NULL (익명화 코드는 탈퇴 API 단계에서 만들고, 여기선 SQL로 같은 상태를 만든다)
  private Long withdrawnMemberId() {
    int n = SEQ.incrementAndGet();
    Member member = memberRepository.save(Member.createLocal(
        "withdrawn" + n + "@test.com", "encoded-password", "탈퇴" + n, String.format("015%08d", n),
        "004", "v1:test-account", null, "홍길동"));
    jdbcTemplate.update("""
        update member
           set status = 'DELETED', email = null, password = null, nickname = null, phone = null,
               bank_code = null, account_no_enc = null, account_holder = null, account_updated_at = null
         where id = ?
        """, member.getId());
    return member.getId();
  }
}
