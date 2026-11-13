package com.justcommit.backend.member.address.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.crypto.EventFieldCipher;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.event.MemberAddressChanged;
import com.justcommit.backend.member.event.SellerAddressesPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 판매자 배송지 목록 → 암호화 전 주소 JSON → 암호화 → MemberAddressChanged (이벤트 생성까지만).
 * 호출 전제: 회원 행 잠금을 확보한 판매자의 주소 변경 트랜잭션 안에서 호출한다.
 * (MANDATORY: 트랜잭션 없이 부르면 예외. 발행은 호출하는 서비스가 같은 트랜잭션에서 한다)
 * - 같은 영속성 컨텍스트 · 기본 AUTO flush 모드에서 조회하므로 같은 트랜잭션에서 방금 바꾼 배송지가 반영된다 (통합 테스트로 확인)
 * - 여기서는 빈 목록 · 기본 배송지 개수만 검사한다. 최대 4개 · 연락처 형식 · 판매자 여부는 각 변경 경로가 보장한다
 * - 주소 JSON은 market과의 약속이라 서비스 전역 Jackson 설정과 분리된 JsonMapper를 쓴다 (EventEnvelopeMapper와 같은 이유)
 * - 평문 주소 · JSON은 로그에 남기지 않는다
 */
@Slf4j
@Component
public class SellerAddressEventFactory {

  private final AddressRepository addressRepository;
  private final EventFieldCipher addressEventCipher;
  private final JsonMapper jsonMapper;

  public SellerAddressEventFactory(AddressRepository addressRepository, EventFieldCipher addressEventCipher) {
    this.addressRepository = addressRepository;
    this.addressEventCipher = addressEventCipher;
    this.jsonMapper = JsonMapper.builder().build();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public MemberAddressChanged create(Long memberId) {
    // memberId로 조회한 목록만 담으므로 이벤트의 memberId와 주소 주인이 같다
    List<SellerAddressesPayload.SellerAddress> addresses =
        addressRepository.findAllByMemberIdOrderByIsDefaultDescIdDesc(memberId).stream()
            .map(SellerAddressesPayload.SellerAddress::from)
            .toList();

    long defaultCount = addresses.stream().filter(SellerAddressesPayload.SellerAddress::isDefault).count();
    if (addresses.isEmpty() || defaultCount != 1) {
      // 주소 내용 없이 개수만 남긴다. 예외를 호출 서비스까지 올려 롤백
      log.error("판매자 배송지 상태 오류: memberId={}, addressCount={}, defaultCount={}",
          memberId, addresses.size(), defaultCount);
      throw new BusinessException(MemberErrorCode.INVALID_SELLER_ADDRESS_STATE);
    }

    String json = jsonMapper.writeValueAsString(new SellerAddressesPayload(memberId, addresses));
    return new MemberAddressChanged(memberId, addressEventCipher.encrypt(json));
  }
}
