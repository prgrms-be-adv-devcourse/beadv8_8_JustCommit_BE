package com.justcommit.backend.market.member.application;

import com.justcommit.backend.market.member.application.SellerAddressReader.InvalidSellerAddressException;
import com.justcommit.backend.market.member.application.SellerAddressReader.SellerAddress;
import com.justcommit.backend.market.member.application.SellerAddressReader.SellerAddresses;
import com.justcommit.backend.market.member.infrastructure.MemberReplicaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberReplicaServiceTest {

  private static final String ENCRYPTED = "v1.k1:encrypted-addresses";

  private final MemberReplicaRepository repository = mock(MemberReplicaRepository.class);
  private final SellerAddressReader reader = mock(SellerAddressReader.class);
  private final MemberReplicaService service = new MemberReplicaService(repository, reader);

  @Test
  @DisplayName("복호화한 memberId가 같으면 받은 암호문 그대로, 시각은 마이크로초로 잘라 저장한다")
  void savesOriginalCiphertextWithMicros() {
    when(reader.read(ENCRYPTED)).thenReturn(addressesOf(7L));
    OffsetDateTime withNanos = OffsetDateTime.parse("2026-11-12T10:00:00.123456789+09:00");

    service.applySellerAddresses(7L, ENCRYPTED, withNanos);

    verify(repository).upsertSellerAddresses(7L, ENCRYPTED,
        OffsetDateTime.parse("2026-11-12T10:00:00.123456+09:00"));
  }

  @Test
  @DisplayName("복호화한 memberId가 이벤트의 memberId와 다르면 저장하지 않고 거절한다")
  void rejectsMemberIdMismatch() {
    when(reader.read(ENCRYPTED)).thenReturn(addressesOf(8L));

    assertThatThrownBy(() -> service.applySellerAddresses(7L, ENCRYPTED,
        OffsetDateTime.parse("2026-11-12T10:00:00+09:00")))
        .isInstanceOf(InvalidSellerAddressException.class);

    verify(repository, never()).upsertSellerAddresses(anyLong(), anyString(), any());
  }

  private static SellerAddresses addressesOf(Long memberId) {
    return new SellerAddresses(memberId, List.of(
        new SellerAddress(15L, true, "홍길동", "01012345678", "06236", "서울 강남구 테헤란로 1", null)));
  }
}
