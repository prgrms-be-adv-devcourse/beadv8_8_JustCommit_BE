package com.justcommit.backend.member.address.infrastructure;

import com.justcommit.backend.member.address.domain.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AddressRepository extends JpaRepository<Address, Long> {

  // 탈퇴 (#407): 회원의 배송지를 한 번에 지운다. 호출한 쪽(토큰 폐기)의 트랜잭션에서 실행된다
  // 같은 트랜잭션의 Member가 분리되지 않도록 clearAutomatically는 쓰지 않는다. 배송지 변경 이벤트는 발행하지 않음
  @Modifying
  @Query("delete from Address a where a.member.id = :memberId")
  int deleteAllByMemberId(@Param("memberId") Long memberId);

  // 본인 배송지만 조회 (다른 회원의 addressId면 빈 값 -> 404)
  Optional<Address> findByIdAndMemberId(Long id, Long memberId);

  // 회원의 기본 배송지 (회원당 1개는 서비스에서 보장)
  Optional<Address> findByMemberIdAndIsDefaultTrue(Long memberId);

  // 배송지 목록 - 기본 배송지 먼저, 나머지는 최근 등록 순
  List<Address> findAllByMemberIdOrderByIsDefaultDescIdDesc(Long memberId);

  // 회원 배송지 개수 (최대 10개)
  long countByMemberId(Long memberId);
}
