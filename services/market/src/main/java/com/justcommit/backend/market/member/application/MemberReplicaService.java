package com.justcommit.backend.market.member.application;

import com.justcommit.backend.market.member.infrastructure.MemberReplicaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

// 회원 이벤트를 복제본에 반영한다. 더 오래된 이벤트는 저장소의 조건부 upsert가 무시한다
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberReplicaService {

  private final MemberReplicaRepository memberReplicaRepository;
  private final SellerAddressReader sellerAddressReader;

  // 가입 · 정보 변경 (바뀐 뒤 전체 값)
  @Transactional
  public void applyChanged(Long memberId, String nickname, String role, boolean seller, OffsetDateTime occurredAt) {
    int changed = memberReplicaRepository.upsertMember(memberId, nickname, role, seller, occurredAt);
    if (changed == 0) {
      log.debug("이미 더 최신 값이 있어 회원 이벤트를 무시함: memberId={}, occurredAt={}", memberId, occurredAt);
    }
  }

  // 탈퇴: 복제본이 없어도 탈퇴 표시 행을 남김
  @Transactional
  public void applyWithdrawn(Long memberId, OffsetDateTime occurredAt) {
    int changed = memberReplicaRepository.markWithdrawn(memberId, occurredAt);
    if (changed == 0) {
      log.debug("이미 더 최신 값이 있어 탈퇴 이벤트를 무시함: memberId={}, occurredAt={}", memberId, occurredAt);
    }
  }

  // 판매자 배송지 목록: 복호화는 memberId 확인에만 쓰고 저장은 받은 암호문 그대로 한다 (평문을 DB에 남기지 않음)
  // 저장된 주소 이벤트 시각보다 새로운 경우에만 반영하고, 같은 시각의 이벤트는 무시
  // DB는 마이크로초까지 저장하므로 시각을 마이크로초로 잘라 비교 (같은 이벤트의 재전송이 같은 시각이 되게)
  @Transactional
  public void applySellerAddresses(Long memberId, String encryptedAddresses, OffsetDateTime occurredAt) {
    SellerAddressReader.SellerAddresses addresses = sellerAddressReader.read(encryptedAddresses);
    if (!memberId.equals(addresses.memberId())) {
      // 다른 회원의 암호문으로 바꿔치기한 이벤트일 수 있어 저장하지 않는다 (로그 · 메시지에는 이벤트의 회원 id만)
      throw new SellerAddressReader.InvalidSellerAddressException("판매자 배송지 memberId 불일치: memberId=" + memberId);
    }

    int changed = memberReplicaRepository.upsertSellerAddresses(memberId, encryptedAddresses,
        occurredAt.truncatedTo(ChronoUnit.MICROS));
    if (changed == 0) {
      log.debug("더 최신 주소가 있거나 탈퇴한 회원이라 판매자 배송지 이벤트를 무시함: memberId={}, occurredAt={}",
          memberId, occurredAt);
    }
  }
}
