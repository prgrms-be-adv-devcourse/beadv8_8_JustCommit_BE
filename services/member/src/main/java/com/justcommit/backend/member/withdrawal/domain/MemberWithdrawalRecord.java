package com.justcommit.backend.member.withdrawal.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * 탈퇴 기록 (#407). 재가입 제한 판정에만 쓰고, 기간이 지나면 스케줄러가 지운다.
 * - 이메일 · 휴대폰은 원문이 아니라 MemberHmac 값(hex 64글자)만 둔다. member_id는 두지 않는다
 * - withdrawnAt은 DB 시각(DatabaseClock). 재가입 판정도 DB 시각 기준으로 비교한다
 */
@Entity
@Table(name = "member_withdrawal_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberWithdrawalRecord extends BaseTimeEntity {

  @Column(name = "email_hash", nullable = false, length = 64)
  private String emailHash;

  @Column(name = "phone_hash", nullable = false, length = 64)
  private String phoneHash;

  @Column(name = "withdrawn_at", nullable = false)
  private Instant withdrawnAt;

  public static MemberWithdrawalRecord create(String emailHash, String phoneHash, Instant withdrawnAt) {
    MemberWithdrawalRecord record = new MemberWithdrawalRecord();
    record.emailHash = Objects.requireNonNull(emailHash, "emailHash");
    record.phoneHash = Objects.requireNonNull(phoneHash, "phoneHash");
    record.withdrawnAt = Objects.requireNonNull(withdrawnAt, "withdrawnAt");
    return record;
  }
}
