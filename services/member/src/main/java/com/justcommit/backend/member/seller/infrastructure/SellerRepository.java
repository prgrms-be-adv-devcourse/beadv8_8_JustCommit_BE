package com.justcommit.backend.member.seller.infrastructure;

import com.justcommit.backend.member.seller.domain.Seller;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SellerRepository extends JpaRepository<Seller, Long> {

  // 판매자와 회원을 함께 읽음 (공개 조회 · 소개글 수정 응답에 닉네임이 필요)
  @Query("select s from Seller s join fetch s.member where s.memberId = :memberId")
  Optional<Seller> findWithMemberById(@Param("memberId") Long memberId);
}
