package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Role;
import com.justcommit.backend.member.domain.Seller;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.repository.SellerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SellerRegisterServiceTest {

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private SellerRepository sellerRepository;

  @InjectMocks
  private SellerRegisterService sellerRegisterService;

  private static final Long MEMBER_ID = 1L;
  private static final String NICKNAME = "떡볶이";
  private static final String INTRO = "다육이 전문 판매자입니다.";
  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 7, 18, 30);

  // 권한별 회원 (id는 DB가 채워 주는 값이라 직접 주입)
  private Member member(Role role) {
    Member member = Member.createLocal("test@gmail.com", "encoded", NICKNAME, "01012345678",
            "004", "v1:encrypted-account", "홍길동");
    ReflectionTestUtils.setField(member, "id", MEMBER_ID);
    ReflectionTestUtils.setField(member, "role", role);
    return member;
  }

  // saveAndFlush 시 Auditing이 created_at을 채워 주는 동작
  private void givenSellerSaved() {
    given(sellerRepository.saveAndFlush(any(Seller.class))).willAnswer(invocation -> {
      Seller seller = invocation.getArgument(0);
      ReflectionTestUtils.setField(seller, "createdAt", CREATED_AT);
      return seller;
    });
  }

  @Test
  @DisplayName("일반 회원이 판매자로 등록하면 SELLER 권한으로 바뀌고 판매자 정보를 저장")
  void register_success() {
    // given
    Member member = member(Role.MEMBER);
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false);
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
    givenSellerSaved();

    // when
    SellerResult result = sellerRegisterService.register(new SellerRegisterCommand(MEMBER_ID, INTRO));

    // then: 응답 (sellerId = memberId)
    assertThat(result.sellerId()).isEqualTo(MEMBER_ID);
    assertThat(result.nickname()).isEqualTo(NICKNAME);
    assertThat(result.intro()).isEqualTo(INTRO);
    assertThat(result.createdAt()).isEqualTo(CREATED_AT);

    // then: 회원 권한 변경
    assertThat(member.getRole()).isEqualTo(Role.SELLER);

    // then: 같은 회원으로 판매자 저장
    ArgumentCaptor<Seller> sellerCaptor = ArgumentCaptor.forClass(Seller.class);
    then(sellerRepository).should().saveAndFlush(sellerCaptor.capture());
    Seller savedSeller = sellerCaptor.getValue();
    assertThat(savedSeller.getMember()).isSameAs(member);
    assertThat(savedSeller.getIntro()).isEqualTo(INTRO);
  }

  @Test
  @DisplayName("ADMIN이 판매자로 등록하면 판매자 정보만 저장하고 권한은 ADMIN 유지")
  void register_admin_keepsRole() {
    Member admin = member(Role.ADMIN);
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false);
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(admin));
    givenSellerSaved();

    sellerRegisterService.register(new SellerRegisterCommand(MEMBER_ID, null));

    assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
    then(sellerRepository).should().saveAndFlush(any(Seller.class));
  }

  @Test
  @DisplayName("이미 판매자면 ALREADY_SELLER, 회원 조회·저장 안 함")
  void register_alreadySeller() {
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(true);

    assertThatThrownBy(() -> sellerRegisterService.register(new SellerRegisterCommand(MEMBER_ID, INTRO)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.ALREADY_SELLER);

    then(memberRepository).shouldHaveNoInteractions();
    then(sellerRepository).should(never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("없는 회원이면 MEMBER_NOT_FOUND, 판매자 저장 안 함")
  void register_memberNotFound() {
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false);
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> sellerRegisterService.register(new SellerRegisterCommand(MEMBER_ID, INTRO)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);

    then(sellerRepository).should(never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("동시 등록으로 DB PK 중복 시 ALREADY_SELLER로 변환")
  void register_duplicateKey() {
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false);
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member(Role.MEMBER)));
    willThrow(new DataIntegrityViolationException("could not execute statement",
            new RuntimeException("duplicate key value violates unique constraint \"seller_pkey\"")))
            .given(sellerRepository).saveAndFlush(any(Seller.class));

    assertThatThrownBy(() -> sellerRegisterService.register(new SellerRegisterCommand(MEMBER_ID, INTRO)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.ALREADY_SELLER);
  }
}
