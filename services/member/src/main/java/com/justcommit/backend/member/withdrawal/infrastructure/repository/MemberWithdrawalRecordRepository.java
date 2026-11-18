package com.justcommit.backend.member.withdrawal.infrastructure.repository;

import com.justcommit.backend.member.withdrawal.domain.MemberWithdrawalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface MemberWithdrawalRecordRepository extends JpaRepository<MemberWithdrawalRecord, Long> {

  // since 이후에 탈퇴한 기록 중 이메일 · 휴대폰 해시가 하나라도 같은 것이 있는지 (재가입 제한, #407)
  @Query("""
      select count(r) > 0 from MemberWithdrawalRecord r
       where r.withdrawnAt > :since
         and (r.emailHash = :emailHash or r.phoneHash = :phoneHash)
      """)
  boolean existsWithdrawnSince(@Param("emailHash") String emailHash,
                               @Param("phoneHash") String phoneHash,
                               @Param("since") Instant since);
}
