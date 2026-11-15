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

  // 회원 행을 잠가 같은 회원의 요청을 한 줄로 처리 (SELECT ... FOR UPDATE)
  // - 배송지 변경 · 판매자 등록: 배송지를 바꾸는 모든 경로(추가 · 기본 변경, 이후 수정 · 삭제 포함)가 회원 행을 먼저 잠가 순서를 통일한다
  // - 재발급(#409): 토큰 발급과 폐기(비밀번호 변경 · 탈퇴)를 같은 잠금으로 줄 세운다
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from Member m where m.id = :id")
  Optional<Member> findByIdForUpdate(@Param("id") Long id);

  // 로그인(#409): 정규화한 이메일로 회원 행을 바로 잠가 조회 (잠그기 전에 불러온 옛 값으로 판단하지 않도록)
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from Member m where m.email = :email")
  Optional<Member> findByEmailForUpdate(@Param("email") String email);

  // 소셜 로그인(#409): 가입 경로 + 소셜 회원 식별값으로 회원 행을 바로 잠가 조회
  // 행이 없을 때(신규 가입)는 잠글 대상이 없음 → 동시 가입은 UNIQUE 제약 · 가입 처리가 막는다
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select m from Member m where m.provider = :provider and m.providerId = :providerId")
  Optional<Member> findByProviderAndProviderIdForUpdate(@Param("provider") Provider provider,
                                                        @Param("providerId") String providerId);
}
