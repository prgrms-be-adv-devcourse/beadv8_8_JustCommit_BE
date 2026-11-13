package com.justcommit.backend.market.member.infrastructure;

import com.justcommit.backend.market.member.domain.MemberReplica;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// 회원 복제본 조건부 upsert를 실제 PostgreSQL(V1 · V2 적용)로 확인한다.
// 같은 이벤트를 두 번, 순서를 바꿔, 탈퇴를 먼저 보내도 복제본이 최신 값이어야 한다 (#275 완료 기준)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class MemberReplicaRepositoryTest {

  private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-12T10:00:00+09:00");
  private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-11-12T10:05:00+09:00");
  private static final OffsetDateTime T3 = OffsetDateTime.parse("2026-11-12T10:10:00+09:00");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired
  private MemberReplicaRepository repository;

  @Autowired
  private EntityManager entityManager;

  @Test
  @DisplayName("처음 받은 가입 이벤트로 복제본이 생긴다")
  void createsReplica() {
    int changed = repository.upsertMember(7L, "초록손", "MEMBER", false, T1);

    MemberReplica replica = read(7L);
    assertThat(changed).isEqualTo(1);
    assertThat(replica.getNickname()).isEqualTo("초록손");
    assertThat(replica.getRole()).isEqualTo("MEMBER");
    assertThat(replica.isSeller()).isFalse();
    assertThat(replica.isWithdrawn()).isFalse();
    assertThat(replica.getLastEventAt()).isEqualTo(T1);
  }

  @Test
  @DisplayName("같은 이벤트를 두 번 받으면 두 번째는 무시한다")
  void ignoresDuplicate() {
    repository.upsertMember(7L, "초록손", "MEMBER", false, T1);

    int changed = repository.upsertMember(7L, "초록손", "MEMBER", false, T1);

    assertThat(changed).isZero();
  }

  @Test
  @DisplayName("순서가 뒤바뀌어 오래된 이벤트가 늦게 와도 최신 값을 덮지 않는다")
  void ignoresOlderEvent() {
    repository.upsertMember(7L, "초록손", "SELLER", true, T2);

    int changed = repository.upsertMember(7L, "초록손", "MEMBER", false, T1);

    MemberReplica replica = read(7L);
    assertThat(changed).isZero();
    assertThat(replica.getRole()).isEqualTo("SELLER");
    assertThat(replica.isSeller()).isTrue();
    assertThat(replica.getLastEventAt()).isEqualTo(T2);
  }

  @Test
  @DisplayName("가입 뒤 탈퇴하면 탈퇴 표시가 되고 닉네임은 남는다")
  void marksWithdrawn() {
    repository.upsertMember(7L, "초록손", "MEMBER", false, T1);

    int changed = repository.markWithdrawn(7L, T2);

    MemberReplica replica = read(7L);
    assertThat(changed).isEqualTo(1);
    assertThat(replica.isWithdrawn()).isTrue();
    assertThat(replica.getNickname()).isEqualTo("초록손");
  }

  @Test
  @DisplayName("탈퇴가 먼저 도착하면 닉네임 없이 탈퇴 행이 생기고, 늦게 온 가입 이벤트가 되살리지 못한다")
  void withdrawnBeforeCreated() {
    repository.markWithdrawn(7L, T2);

    int changed = repository.upsertMember(7L, "초록손", "MEMBER", false, T1);

    MemberReplica replica = read(7L);
    assertThat(changed).isZero();
    assertThat(replica.isWithdrawn()).isTrue();
    assertThat(replica.getNickname()).isNull();
  }

  @Test
  @DisplayName("탈퇴 뒤 더 늦은 정보 변경 이벤트가 와도 탈퇴 표시는 풀리지 않는다")
  void staysWithdrawn() {
    repository.upsertMember(7L, "초록손", "MEMBER", false, T1);
    repository.markWithdrawn(7L, T2);

    repository.upsertMember(7L, "초록손", "SELLER", true, T3);

    assertThat(read(7L).isWithdrawn()).isTrue();
  }

  @Test
  @DisplayName("정보 변경과 같은 시각의 탈퇴도 반영한다 (탈퇴는 마지막 상태라 같은 시각이면 탈퇴가 이김)")
  void withdrawnAtSameTimeWins() {
    repository.upsertMember(7L, "초록손", "MEMBER", false, T1);

    int changed = repository.markWithdrawn(7L, T1);

    assertThat(changed).isEqualTo(1);
    assertThat(read(7L).isWithdrawn()).isTrue();
  }

  // ── 판매자 배송지 (member.address.events.v1) ──

  @Test
  @DisplayName("주소 이벤트가 먼저 오면 주소만 있는 행이 생기고(회원 칸은 비어 있음), 뒤에 온 회원 이벤트가 정상 반영된다")
  void addressesBeforeMember() {
    int saved = repository.upsertSellerAddresses(7L, "v1.k1:first", T2);

    MemberReplica addressOnly = read(7L);
    assertThat(saved).isEqualTo(1);
    assertThat(addressOnly.getNickname()).isNull();
    assertThat(addressOnly.getRole()).isNull();
    assertThat(addressOnly.isSeller()).isFalse();
    assertThat(addressOnly.isWithdrawn()).isFalse();
    // 회원 이벤트를 아직 안 받았다는 표시 (1970-01-01). 회원 이벤트 시각은 항상 이보다 뒤라는 전제
    assertThat(addressOnly.getLastEventAt().toInstant()).isEqualTo(Instant.EPOCH);
    assertThat(addressOnly.getSellerAddressesEnc()).isEqualTo("v1.k1:first");

    int changed = repository.upsertMember(7L, "초록손", "SELLER", true, T1);

    MemberReplica replica = read(7L);
    assertThat(changed).isEqualTo(1);
    assertThat(replica.getNickname()).isEqualTo("초록손");
    assertThat(replica.isSeller()).isTrue();
    assertThat(replica.getSellerAddressesEnc()).isEqualTo("v1.k1:first");
  }

  @Test
  @DisplayName("회원 이벤트가 먼저 와 있어도 주소가 저장된다")
  void addressesAfterMember() {
    repository.upsertMember(7L, "초록손", "SELLER", true, T1);

    int changed = repository.upsertSellerAddresses(7L, "v1.k1:first", T2);

    MemberReplica replica = read(7L);
    assertThat(changed).isEqualTo(1);
    assertThat(replica.getNickname()).isEqualTo("초록손");
    assertThat(replica.getSellerAddressesEnc()).isEqualTo("v1.k1:first");
    assertThat(replica.getSellerAddressesEventAt()).isAtSameInstantAs(T2);
  }

  @Test
  @DisplayName("저장된 주소 이벤트 시각보다 새로운 경우에만 반영하고, 오래된 이벤트와 같은 시각의 이벤트는 무시한다")
  void addressesOnlyNewer() {
    repository.upsertSellerAddresses(7L, "v1.k1:second", T2);

    int newer = repository.upsertSellerAddresses(7L, "v1.k1:third", T3);
    int older = repository.upsertSellerAddresses(7L, "v1.k1:first", T1);
    int sameTime = repository.upsertSellerAddresses(7L, "v1.k1:other", T3);

    MemberReplica replica = read(7L);
    assertThat(newer).isEqualTo(1);
    assertThat(older).isZero();
    assertThat(sameTime).isZero();
    assertThat(replica.getSellerAddressesEnc()).isEqualTo("v1.k1:third");
    assertThat(replica.getSellerAddressesEventAt()).isAtSameInstantAs(T3);
  }

  @Test
  @DisplayName("회원 정보 이벤트는 주소를 유지한다")
  void memberEventKeepsAddresses() {
    repository.upsertSellerAddresses(7L, "v1.k1:first", T1);

    repository.upsertMember(7L, "새닉네임", "SELLER", true, T2);

    assertThat(read(7L).getSellerAddressesEnc()).isEqualTo("v1.k1:first");
  }

  @Test
  @DisplayName("주소를 받은 뒤 탈퇴하면 탈퇴 표시와 함께 주소 암호문이 비워진다")
  void withdrawnClearsAddresses() {
    repository.upsertMember(7L, "초록손", "SELLER", true, T1);
    repository.upsertSellerAddresses(7L, "v1.k1:first", T2);

    int changed = repository.markWithdrawn(7L, T3);

    MemberReplica replica = read(7L);
    assertThat(changed).isEqualTo(1);
    assertThat(replica.isWithdrawn()).isTrue();
    assertThat(replica.getSellerAddressesEnc()).isNull();
  }

  @Test
  @DisplayName("탈퇴 뒤 늦게 도착한 주소 이벤트는 시각과 관계없이 저장하지 않는다")
  void addressesAfterWithdrawnIgnored() {
    repository.markWithdrawn(7L, T2);

    int older = repository.upsertSellerAddresses(7L, "v1.k1:late", T1);
    int newer = repository.upsertSellerAddresses(7L, "v1.k1:late", T3);

    MemberReplica replica = read(7L);
    assertThat(older).isZero();
    assertThat(newer).isZero();
    assertThat(replica.isWithdrawn()).isTrue();
    assertThat(replica.getSellerAddressesEnc()).isNull();
  }

  // native 쿼리는 영속성 컨텍스트를 거치지 않으므로, 캐시를 비우고 DB에서 다시 읽는다
  private MemberReplica read(Long memberId) {
    entityManager.clear();
    return repository.findById(memberId).orElseThrow();
  }
}
