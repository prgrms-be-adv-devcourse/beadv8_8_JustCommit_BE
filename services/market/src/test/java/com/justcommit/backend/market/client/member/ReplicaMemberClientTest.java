package com.justcommit.backend.market.client.member;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.member.domain.MemberReplica;
import com.justcommit.backend.market.member.infrastructure.MemberReplicaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplicaMemberClientTest {

  private final MemberReplicaRepository repository = mock(MemberReplicaRepository.class);
  private final ReplicaMemberClient client = new ReplicaMemberClient(repository);

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
  @DisplayName("판매자 기본 배송지는 복제본이 아직 없어 503 (#275 후속)")
  void defaultAddressStillUnavailable() {
    assertThatThrownBy(() -> client.getDefaultAddress(7L)).isInstanceOf(BusinessException.class);
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
