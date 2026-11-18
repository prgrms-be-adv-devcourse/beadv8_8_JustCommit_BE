package com.justcommit.backend.member.withdrawal.infrastructure.repository;

import com.justcommit.backend.member.withdrawal.domain.MemberWithdrawalRecord;
import org.springframework.data.jpa.repository.JpaRepository;

// 조회 메서드(30일 이내 기록 · 만료 기록 삭제)는 재가입 제한 · 정리 스케줄러 단계에서 추가한다
public interface MemberWithdrawalRecordRepository extends JpaRepository<MemberWithdrawalRecord, Long> {
}
