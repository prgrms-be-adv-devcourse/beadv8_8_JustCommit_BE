package com.justcommit.backend.market.client.member;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.member.application.SellerAddressReader;
import com.justcommit.backend.market.member.application.SellerAddressReader.InvalidSellerAddressException;
import com.justcommit.backend.market.member.application.SellerAddressReader.SellerAddress;
import com.justcommit.backend.market.member.application.SellerAddressReader.SellerAddresses;
import com.justcommit.backend.market.member.domain.MemberReplica;
import com.justcommit.backend.market.member.infrastructure.MemberReplicaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplicaMemberClientTest {

  private final MemberReplicaRepository repository = mock(MemberReplicaRepository.class);
  private final SellerAddressReader sellerAddressReader = mock(SellerAddressReader.class);
  private final ReplicaMemberClient client = new ReplicaMemberClient(repository, sellerAddressReader);

  @Test
  @DisplayName("복제본이 있으면 닉네임, 탈퇴했으면 「탈퇴한 회원」, 없으면 「알 수 없는 회원」을 빠짐없이 돌려준다")
  void nicknames() {
    List<Long> ids = List.of(7L, 8L, 9L);
    // 가짜 객체는 when(...) 밖에서 먼저 만든다 (when 안에서 또 when을 부르면 Mockito가 설정이 안 끝났다고 봄)
    List<MemberReplica> replicas = List.of(replica(7L, "초록손", false), replica(8L, "민트초코", true));
    when(repository.findAllById(ids)).thenReturn(replicas);

    Map<Long, String> nicknames = client.getNicknames(ids);

    assertThat(nicknames).containsOnlyKeys(7L, 8L, 9L);
    assertThat(nicknames.get(7L)).isEqualTo("초록손");
    assertThat(nicknames.get(8L)).isEqualTo(ReplicaMemberClient.WITHDRAWN_NICKNAME);
    assertThat(nicknames.get(9L)).isEqualTo(ReplicaMemberClient.UNKNOWN_NICKNAME);
  }

  @Test
  @DisplayName("탈퇴가 먼저 와서 닉네임이 없는 복제본도 「탈퇴한 회원」으로 보인다")
  void withdrawnWithoutNickname() {
    List<Long> ids = List.of(7L);
    List<MemberReplica> replicas = List.of(replica(7L, null, true));
    when(repository.findAllById(ids)).thenReturn(replicas);

    assertThat(client.getNicknames(ids).get(7L)).isEqualTo(ReplicaMemberClient.WITHDRAWN_NICKNAME);
  }

  @Test
  @DisplayName("복제본이 없으면 판매자 배송지를 쓸 수 없다 (응답이면 503, 택배 예약은 보류)")
  void defaultAddressUnavailableWithoutReplica() {
    assertThatThrownBy(() -> client.getDefaultAddress(7L)).isInstanceOf(SellerAddressUnavailableException.class);
  }

  @Test
  @DisplayName("탈퇴 여부: 복제본에 탈퇴 표시가 있으면 true, 복제본이 아직 없으면 false")
  void isWithdrawn() {
    MemberReplica withdrawn = replica(7L, "초록손", true);
    when(repository.findById(7L)).thenReturn(Optional.of(withdrawn));
    when(repository.findById(9L)).thenReturn(Optional.empty());

    assertThat(client.isWithdrawn(7L)).isTrue();
    assertThat(client.isWithdrawn(9L)).isFalse();
  }

  @Test
  @DisplayName("주소 암호문이 있으면 기본 주소(isDefault)를 택배 예약용 주소로 돌려준다")
  void defaultAddressFromReplica() {
    MemberReplica replica = replicaWithAddresses(7L, "v1.k1:addresses");
    when(repository.findById(7L)).thenReturn(Optional.of(replica));
    SellerAddresses addresses = new SellerAddresses(7L, List.of(
        address(12L, false, "서울 중구 세종대로 110", null),
        address(15L, true, "서울 강남구 테헤란로 1", "5층")));
    when(sellerAddressReader.read("v1.k1:addresses")).thenReturn(addresses);

    MemberAddress result = client.getDefaultAddress(7L);

    assertThat(result.addressId()).isEqualTo(15L);
    assertThat(result.address1()).isEqualTo("서울 강남구 테헤란로 1");
    assertThat(result.address2()).isEqualTo("5층");
  }

  @Test
  @DisplayName("주소 암호문이 없으면(아직 안 옴 · 탈퇴로 지움) 판매자 배송지를 쓸 수 없다")
  void defaultAddressUnavailableWithoutCiphertext() {
    MemberReplica replica = replicaWithAddresses(7L, null);
    when(repository.findById(7L)).thenReturn(Optional.of(replica));

    assertThatThrownBy(() -> client.getDefaultAddress(7L)).isInstanceOf(SellerAddressUnavailableException.class);
  }

  @Test
  @DisplayName("기본 주소가 없으면 판매자 배송지를 쓸 수 없다")
  void defaultAddressUnavailableWithoutDefault() {
    MemberReplica replica = replicaWithAddresses(7L, "v1.k1:addresses");
    when(repository.findById(7L)).thenReturn(Optional.of(replica));
    SellerAddresses addresses = new SellerAddresses(7L, List.of(address(12L, false, "서울 중구 세종대로 110", null)));
    when(sellerAddressReader.read("v1.k1:addresses")).thenReturn(addresses);

    assertThatThrownBy(() -> client.getDefaultAddress(7L)).isInstanceOf(SellerAddressUnavailableException.class);
  }

  @Test
  @DisplayName("저장된 암호문을 읽지 못하면 Reader 예외를 그대로 전달한다 (주소 없음과 구분)")
  void storedCiphertextBrokenPropagatesReaderException() {
    MemberReplica replica = replicaWithAddresses(7L, "v1.k1:broken");
    when(repository.findById(7L)).thenReturn(Optional.of(replica));
    when(sellerAddressReader.read("v1.k1:broken")).thenThrow(new InvalidSellerAddressException("판매자 배송지 복호화 실패"));

    assertThatThrownBy(() -> client.getDefaultAddress(7L))
        .isInstanceOf(InvalidSellerAddressException.class)
        .isNotInstanceOf(SellerAddressUnavailableException.class);
  }

  private static MemberReplica replicaWithAddresses(Long memberId, String encryptedAddresses) {
    MemberReplica replica = mock(MemberReplica.class);
    when(replica.getMemberId()).thenReturn(memberId);
    when(replica.getSellerAddressesEnc()).thenReturn(encryptedAddresses);
    return replica;
  }

  private static SellerAddress address(Long addressId, boolean isDefault, String address1, String address2) {
    return new SellerAddress(addressId, isDefault, "홍길동", "01012345678", "06236", address1, address2);
  }

  // MemberReplica는 저장소의 upsert로만 만들어서, 테스트에서는 값만 흉내 낸다
  private static MemberReplica replica(Long memberId, String nickname, boolean withdrawn) {
    MemberReplica replica = mock(MemberReplica.class);
    when(replica.getMemberId()).thenReturn(memberId);
    when(replica.getNickname()).thenReturn(nickname);
    when(replica.isWithdrawn()).thenReturn(withdrawn);
    return replica;
  }
}
