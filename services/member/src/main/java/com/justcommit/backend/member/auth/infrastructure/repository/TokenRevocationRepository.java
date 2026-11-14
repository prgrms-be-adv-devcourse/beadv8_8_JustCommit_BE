package com.justcommit.backend.member.auth.infrastructure.repository;

import com.justcommit.backend.member.auth.domain.TokenRevocation;
import org.springframework.data.jpa.repository.JpaRepository;

// 회원 토큰 폐기 작업 (#409). 재처리용 조회는 스케줄러 작업에서 추가
public interface TokenRevocationRepository extends JpaRepository<TokenRevocation, Long> {
}
