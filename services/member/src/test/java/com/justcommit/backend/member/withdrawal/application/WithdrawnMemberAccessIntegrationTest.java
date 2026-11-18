package com.justcommit.backend.member.withdrawal.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.address.application.AddressCreateCommand;
import com.justcommit.backend.member.address.application.AddressService;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.member.application.MyInfoService;
import com.justcommit.backend.member.seller.application.SellerRegisterCommand;
import com.justcommit.backend.member.seller.application.SellerRegisterService;
import com.justcommit.backend.member.seller.domain.SellerReviewConsentPolicy;
import com.justcommit.backend.member.seller.infrastructure.SellerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [S-A11] 탈퇴 회원 차단 (#407, DELETED 범위).
 * 토큰 폐기 기록이 PENDING이면 기존 Access가 Gateway를 지나올 수 있으므로, member 서비스가 회원 상태로 한 번 더 막는다.
 * 탈퇴 직후 모습(DELETED + 계좌 NULL)을 만들어, 복호화 오류(500)가 아니라 404로 끝나고 아무것도 저장되지 않는지 확인한다.
 * INACTIVE 차단은 #422 범위.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class WithdrawnMemberAccessIntegrationTest {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

  private static final KeyPair JWT_KEY_PAIR = newKeyPair();
  private static final AtomicInteger SEQ = new AtomicInteger();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired
  private MyInfoService myInfoService;

  @Autowired
  private AddressService addressService;

  @Autowired
  private SellerRegisterService sellerRegisterService;

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

  private static KeyPair newKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
