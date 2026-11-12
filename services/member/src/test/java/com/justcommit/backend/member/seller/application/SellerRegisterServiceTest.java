package com.justcommit.backend.member.seller.application;

import com.justcommit.backend.member.event.MemberUpdated;
import com.justcommit.backend.member.event.MemberAddressChanged;
import com.justcommit.backend.member.address.application.SellerAddressEventFactory;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Role;
import com.justcommit.backend.member.seller.domain.Seller;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.seller.infrastructure.SellerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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

  @Mock
  private OutboxPublisher outboxPublisher;

  @Mock
  private SellerAddressEventFactory sellerAddressEventFactory;

  @InjectMocks
  private SellerRegisterService sellerRegisterService;

  private static final Long MEMBER_ID = 1L;
  private static final String NICKNAME = "떡볶이";
  private static final String INTRO = "다육이 전문 판매자입니다.";
  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 7, 18, 30);
  // 팩토리가 만든 주소 이벤트 (암호문 내용은 팩토리 테스트에서 확인)
  private static final MemberAddressChanged ADDRESS_EVENT = new MemberAddressChanged(MEMBER_ID, "v1.k1:test");

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
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member));
    given(sellerAddressEventFactory.create(MEMBER_ID)).willReturn(ADDRESS_EVENT);
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

    // then: 다른 서비스에 판매자 등록을 알림
    then(outboxPublisher).should().publish(new MemberUpdated(MEMBER_ID, NICKNAME, "SELLER", true));
    then(outboxPublisher).should().publish(ADDRESS_EVENT);
  }

  @Test
  @DisplayName("ADMIN이 판매자로 등록하면 판매자 정보만 저장하고 권한은 ADMIN 유지")
  void register_admin_keepsRole() {
    Member admin = member(Role.ADMIN);
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false);
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(admin));
    given(sellerAddressEventFactory.create(MEMBER_ID)).willReturn(ADDRESS_EVENT);
    givenSellerSaved();

    sellerRegisterService.register(new SellerRegisterCommand(MEMBER_ID, null));

    assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
    // ADMIN은 role이 그대로라 seller로 판매자임을 알림
    then(outboxPublisher).should().publish(new MemberUpdated(MEMBER_ID, NICKNAME, "ADMIN", true));
    then(outboxPublisher).should().publish(ADDRESS_EVENT);
    then(sellerRepository).should().saveAndFlush(any(Seller.class));
  }

  @Test
  @DisplayName("이미 판매자면 ALREADY_SELLER, 저장·발행 안 함 (회원 잠금 뒤에 확인)")
  void register_alreadySeller() {
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member(Role.SELLER)));
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(true);

    assertThatThrownBy(() -> sellerRegisterService.register(new SellerRegisterCommand(MEMBER_ID, INTRO)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.ALREADY_SELLER);

    then(sellerRepository).should(never()).saveAndFlush(any());
    then(outboxPublisher).should(never()).publish(any());
  }

  @Test
  @DisplayName("없는 회원이면 MEMBER_NOT_FOUND, 판매자 저장 안 함")
  void register_memberNotFound() {
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> sellerRegisterService.register(new SellerRegisterCommand(MEMBER_ID, INTRO)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);

    then(sellerRepository).should(never()).saveAndFlush(any());
    then(outboxPublisher).should(never()).publish(any());
  }

  @Test
  @DisplayName("동시 등록으로 DB PK 중복 시 ALREADY_SELLER로 변환")
  void register_duplicateKey() {
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false);
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member(Role.MEMBER)));
    willThrow(new DataIntegrityViolationException("could not execute statement",
            new RuntimeException("duplicate key value violates unique constraint \"seller_pkey\"")))
            .given(sellerRepository).saveAndFlush(any(Seller.class));

    assertThatThrownBy(() -> sellerRegisterService.register(new SellerRegisterCommand(MEMBER_ID, INTRO)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.ALREADY_SELLER);

    // 저장이 실패했으니 판매자 등록 이벤트도 남지 않음
    then(outboxPublisher).should(never()).publish(any());
  }

  @Test
  @DisplayName("배송지 상태가 깨져 주소 이벤트를 못 만들면 권한 변경 · 판매자 저장 · 발행을 하지 않음")
  void register_invalidAddressState() {
    Member member = member(Role.MEMBER);
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member));
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false);
    given(sellerAddressEventFactory.create(MEMBER_ID))
            .willThrow(new BusinessException(MemberErrorCode.INVALID_SELLER_ADDRESS_STATE));

    assertThatThrownBy(() -> sellerRegisterService.register(new SellerRegisterCommand(MEMBER_ID, INTRO)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.INVALID_SELLER_ADDRESS_STATE);

    assertThat(member.getRole()).isEqualTo(Role.MEMBER);
    then(sellerRepository).should(never()).saveAndFlush(any());
    then(outboxPublisher).should(never()).publish(any());
  }

  @Test
  @DisplayName("판매자 등록 이벤트를 먼저, 판매자 배송지 목록 이벤트를 그다음에 발행")
  void register_publishOrder() {
    Member member = member(Role.MEMBER);
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member));
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false);
    given(sellerAddressEventFactory.create(MEMBER_ID)).willReturn(ADDRESS_EVENT);
    givenSellerSaved();

    sellerRegisterService.register(new SellerRegisterCommand(MEMBER_ID, INTRO));

    InOrder inOrder = inOrder(outboxPublisher);
    inOrder.verify(outboxPublisher).publish(new MemberUpdated(MEMBER_ID, NICKNAME, "SELLER", true));
    inOrder.verify(outboxPublisher).publish(ADDRESS_EVENT);
  }
}
