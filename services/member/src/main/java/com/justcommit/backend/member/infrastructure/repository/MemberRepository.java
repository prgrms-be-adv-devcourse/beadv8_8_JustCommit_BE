package com.justcommit.backend.member.infrastructure.repository;

import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Provider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

  boolean existsByEmail(String email);

  boolean existsByNickname(String nickname);

  boolean existsByPhone(String phone);

  // 로그인: 정규화한 이메일로 회원 조회
  Optional<Member> findByEmail(String email);

  // 소셜 로그인: 가입 경로 + 소셜 회원 식별값으로 회원 조회
  Optional<Member> findByProviderAndProviderId(Provider provider, String providerId);

  // 배송지 변경 · 판매자 등록: 회원 행을 잠가 같은 회원의 요청을 한 줄로 처리 (SELECT ... FOR UPDATE)
  // 배송지를 바꾸는 모든 경로(추가 · 기본 변경, 이후 수정 · 삭제 포함)가 회원 행을 먼저 잠가 순서를 통일한다
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from Member m where m.id = :id")
  Optional<Member> findByIdForUpdate(@Param("id") Long id);
}
