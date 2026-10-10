package com.justcommit.backend.member.infrastructure.repository;

import com.justcommit.backend.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

  boolean existsByEmail(String email);

  boolean existsByNickname(String nickname);

  boolean existsByPhone(String phone);

  // 로그인: 정규화한 이메일로 회원 조회
  Optional<Member> findByEmail(String email);
}
