package com.justcommit.backend.market.client.member;

import com.justcommit.backend.market.member.application.SellerAddressReader;
import com.justcommit.backend.market.member.domain.MemberReplica;
import com.justcommit.backend.market.member.infrastructure.MemberReplicaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

// 회원 정보는 member를 부르지 않고 market의 회원 복제본(member.events.v1로 채움)에서 읽는다 (#275)
@Slf4j
@Component
@RequiredArgsConstructor
class ReplicaMemberClient implements MemberQuery {

  static final String WITHDRAWN_NICKNAME = "탈퇴한 회원";
  // TODO: 복제본이 아직 없을 때 표시 문구 · member REST 보완 여부 정한 뒤 확정
  static final String UNKNOWN_NICKNAME = "알 수 없는 회원";

  private final MemberReplicaRepository memberReplicaRepository;
  private final SellerAddressReader sellerAddressReader;

  // 판매자 기본 배송지: 복제본의 주소 암호문을 읽어 기본 주소를 돌려준다 (#275).
  // 임시 연결: 재예약 때마다 그 시점의 최신 기본 주소를 읽는다. 수락 당시 고른 주소 고정(스냅샷)은 아직 아니며,
  // #283 · 배송 담당과 주소 스냅샷 연결을 마쳐야 한다.
  // 주소를 쓸 수 없으면(복제본 · 암호문 · 기본 주소 없음) 예외만 던지고, 기록은 택배 예약 쪽이 한다.
  // 저장된 암호문을 못 읽는 경우는 Reader 예외를 그대로 둔다 (메시지 · 원인에 평문 없음)
  @Override
  public MemberAddress getDefaultAddress(Long memberId) {
    String encryptedAddresses = memberReplicaRepository.findById(memberId)
        .map(MemberReplica::getSellerAddressesEnc)
        .orElseThrow(SellerAddressUnavailableException::new);

    return sellerAddressReader.read(encryptedAddresses).addresses().stream()
        .filter(SellerAddressReader.SellerAddress::isDefault)
        .findFirst()
        .map(address -> new MemberAddress(address.addressId(), address.recipientName(), address.recipientPhone(),
            address.zipcode(), address.address1(), address.address2()))
        .orElseThrow(SellerAddressUnavailableException::new);
  }

  // 복제본이 아직 없으면(이벤트가 안 옴) 503 대신 기본 문구. 닉네임은 화면 표시용이라 장바구니 · 주문을 막지 않게
  @Override
  public Map<Long, String> getNicknames(Collection<Long> memberIds) {
    Map<Long, String> nicknames = new HashMap<>();
    for (MemberReplica replica : memberReplicaRepository.findAllById(memberIds)) {
      nicknames.put(replica.getMemberId(), displayName(replica));
    }
    for (Long memberId : memberIds) {
      if (!nicknames.containsKey(memberId)) {
        log.warn("회원 복제본이 없어 기본 문구로 표시: memberId={}", memberId);
        nicknames.put(memberId, UNKNOWN_NICKNAME);
      }
    }
    return nicknames;
  }

  // 복제본이 아직 없으면 탈퇴로 보지 않음: 이벤트가 안 온 것뿐이고, 탈퇴 직후는 Gateway가 토큰을 막음
  @Override
  public boolean isWithdrawn(Long memberId) {
    return memberReplicaRepository.findById(memberId)
        .map(MemberReplica::isWithdrawn)
        .orElse(false);
  }

  private static String displayName(MemberReplica replica) {
    if (replica.isWithdrawn()) {
      return WITHDRAWN_NICKNAME;
    }
    // 주소 이벤트가 먼저 와 회원 칸이 아직 비어 있을 수 있어, 빈칸으로 보이지 않게
    return replica.getNickname() != null ? replica.getNickname() : UNKNOWN_NICKNAME;
  }
}
