package com.justcommit.backend.member.unlink.application;

import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import com.justcommit.backend.member.unlink.domain.KakaoUnlinkReceipt;
import com.justcommit.backend.member.unlink.domain.KakaoUnlinkStatus;
import com.justcommit.backend.member.unlink.infrastructure.repository.KakaoUnlinkReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 카카오 연결 해제 웹훅 처리 운영자 재개 (#422, 흐름 문서 「재시도 · 실패 · 재개」).
 * FAILED인 기록만 재개할 단계로 되돌린다. 무효(INVALIDATED) 등 다른 단계는 재개하지 않는다.
 * 잠금 순서는 처리와 같은 회원 → 기록. 회원이 아직 연결되지 않은 기록(PENDING에서 실패)은 기록만 잠근다
 */
@Service
@RequiredArgsConstructor
public class KakaoUnlinkResumeService {

  private final TransactionTemplate transactionTemplate;
  private final KakaoUnlinkReceiptRepository receiptRepository;
  private final MemberLocker memberLocker;
  private final DatabaseClock databaseClock;

  /**
   * @return 재개했으면 true (FAILED가 아니거나 없는 기록이면 false)
   */
  public boolean resume(Long receiptId) {
    Long memberId = receiptRepository.findById(receiptId).map(KakaoUnlinkReceipt::getMemberId).orElse(null);
    Boolean resumed = transactionTemplate.execute(status -> {
      if (memberId != null) {
        memberLocker.lockById(memberId);
      }
      return receiptRepository.findByIdForUpdate(receiptId)
          .filter(receipt -> receipt.getStatus() == KakaoUnlinkStatus.FAILED)
          .map(receipt -> {
            receipt.resume(databaseClock.now());
            return true;
          })
          .orElse(false);
    });
    return Boolean.TRUE.equals(resumed);
  }
}
