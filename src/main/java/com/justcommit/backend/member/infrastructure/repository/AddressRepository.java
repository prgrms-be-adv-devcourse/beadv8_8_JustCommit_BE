package com.justcommit.backend.member.infrastructure.repository;

import com.justcommit.backend.member.domain.Address;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AddressRepository extends JpaRepository<Address, Long> {

  // 본인 배송지만 조회 (다른 회원의 addressId면 빈 값 -> 404)
  Optional<Address> findByIdAndMemberId(Long id, Long memberId);

  // 회원의 기본 배송지 (회원당 1개는 서비스에서 보장)
  Optional<Address> findByMemberIdAndIsDefaultTrue(Long memberId);
}
