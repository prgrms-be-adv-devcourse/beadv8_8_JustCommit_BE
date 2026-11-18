package com.justcommit.backend.member.address.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.common.messaging.crypto.EventFieldCipher;
import com.justcommit.backend.member.address.domain.Address;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.event.MemberAddressChanged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class SellerAddressEventFactoryTest {

  private static final Long MEMBER_ID = 7L;

  @Mock
  AddressRepository addressRepository;

  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private EventFieldCipher cipher;
  private SellerAddressEventFactory factory;

  @BeforeEach
  void setUp() {
    // 실행마다 새 키 (레포에 키 값이 남지 않게)
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    cipher = new EventFieldCipher("k1", Map.of("k1", Base64.getEncoder().encodeToString(key)));
    // 단위 테스트라 직접 만든다. 트랜잭션(MANDATORY) 검사는 통합 테스트에서 Spring 프록시로 확인
    factory = new SellerAddressEventFactory(addressRepository, cipher);
  }

  private Address address(Long id, boolean isDefault, String address2) {
    Address address = Address.create(mock(Member.class), "홍길동", "01012345678", "06236",
            "서울 강남구 테헤란로 1", address2, "회사", isDefault);
    ReflectionTestUtils.setField(address, "id", id);
    return address;
  }

  @Test
  @DisplayName("배송지 목록을 암호화한 이벤트를 만들고, 복호화하면 약속한 JSON 형식이다 (address2 null 포함, addressName 없음)")
  @SuppressWarnings("unchecked")
  void create() {
    // 가짜 Repository가 기본 배송지부터 돌려준다. 팩토리가 그 순서를 유지하는지만 확인 (실제 DB 정렬은 통합 테스트)
    given(addressRepository.findAllByMemberIdOrderByIsDefaultDescIdDesc(MEMBER_ID))
            .willReturn(List.of(address(12L, true, "5층"), address(15L, false, null)));

    MemberAddressChanged event = factory.create(MEMBER_ID);

    assertThat(event.memberId()).isEqualTo(MEMBER_ID);
    assertThat(event.topic()).isEqualTo(KafkaTopics.MEMBER_ADDRESS_EVENTS);
    assertThat(event.aggregateId()).isEqualTo("7");
    assertThat(event.encryptedAddresses()).startsWith("v1.k1:");

    String json = cipher.decrypt(event.encryptedAddresses());
    // null 칸을 생략하지 않고, 배송지 이름은 넣지 않는다
    assertThat(json).contains("\"address2\":null").doesNotContain("addressName").doesNotContain("회사");

    Map<String, Object> root = jsonMapper.readValue(json, Map.class);
    assertThat(root).containsOnlyKeys("memberId", "addresses");
    assertThat(((Number) root.get("memberId")).longValue()).isEqualTo(MEMBER_ID);

    List<Map<String, Object>> addresses = (List<Map<String, Object>>) root.get("addresses");
    assertThat(addresses).hasSize(2);

    Map<String, Object> first = addresses.get(0);
    assertThat(first).containsOnlyKeys("addressId", "isDefault", "recipientName", "recipientPhone",
            "zipcode", "address1", "address2");
    assertThat(((Number) first.get("addressId")).longValue()).isEqualTo(12L);
    assertThat(first).containsEntry("isDefault", true)
            .containsEntry("recipientName", "홍길동")
            .containsEntry("recipientPhone", "01012345678")
            .containsEntry("zipcode", "06236")
            .containsEntry("address1", "서울 강남구 테헤란로 1")
            .containsEntry("address2", "5층");

    Map<String, Object> second = addresses.get(1);
    assertThat(((Number) second.get("addressId")).longValue()).isEqualTo(15L);
    assertThat(second).containsEntry("isDefault", false).containsEntry("address2", null);
  }

  @Test
  @DisplayName("배송지가 없으면 INVALID_SELLER_ADDRESS_STATE")
  void create_empty() {
    given(addressRepository.findAllByMemberIdOrderByIsDefaultDescIdDesc(MEMBER_ID)).willReturn(List.of());

    assertInvalidState();
  }

  @Test
  @DisplayName("기본 배송지가 없으면 INVALID_SELLER_ADDRESS_STATE")
  void create_noDefault() {
    given(addressRepository.findAllByMemberIdOrderByIsDefaultDescIdDesc(MEMBER_ID))
            .willReturn(List.of(address(12L, false, null), address(15L, false, null)));

    assertInvalidState();
  }

  @Test
  @DisplayName("기본 배송지가 여러 개면 INVALID_SELLER_ADDRESS_STATE")
  void create_twoDefaults() {
    given(addressRepository.findAllByMemberIdOrderByIsDefaultDescIdDesc(MEMBER_ID))
            .willReturn(List.of(address(12L, true, null), address(15L, true, null)));

    assertInvalidState();
  }

  private void assertInvalidState() {
    assertThatThrownBy(() -> factory.create(MEMBER_ID))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.INVALID_SELLER_ADDRESS_STATE);
  }
}
