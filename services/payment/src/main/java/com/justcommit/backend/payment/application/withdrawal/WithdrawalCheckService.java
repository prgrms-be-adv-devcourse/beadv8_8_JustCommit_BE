package com.justcommit.backend.payment.application.withdrawal;

import com.justcommit.backend.payment.domain.CashOutStatus;
import com.justcommit.backend.payment.domain.ChargeStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.infrastructure.repository.CashOutRepository;
import com.justcommit.backend.payment.infrastructure.repository.ChargeRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.presentation.internal.dto.WithdrawalCheckResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

// member가 탈퇴 전에 부르는 회원 금융 상태 확인. DB 오류는 잡지 않고 5xx로 보낸다 (member가 Fail-closed로 막음)
// 지갑을 잠그지 않는다: 확인과 탈퇴 커밋 사이에 반영된 충전은 드문 경우라 따로 막지 않는다 (#329)
@Service
@RequiredArgsConstructor
public class WithdrawalCheckService {

    // 결과가 아직 확정되지 않은 충전. READY는 승인 요청 전이라 돈이 움직이지 않았으므로 제외
    static final Set<ChargeStatus> IN_PROGRESS_CHARGE_STATUSES =
            Set.of(ChargeStatus.PENDING, ChargeStatus.REVIEW_REQUIRED);

    private final WalletRepository walletRepository;
    private final ChargeRepository chargeRepository;
    private final CashOutRepository cashOutRepository;

    @Transactional(readOnly = true)
    public WithdrawalCheckResponse check(Long memberId) {
        // 지갑이 없는 회원은 차단 사유 없음. 여기서 지갑을 만들지 않는다
        Optional<Wallet> found = walletRepository.findByMemberId(memberId);
        if (found.isEmpty()) {
            return WithdrawalCheckResponse.noWallet();
        }
        Wallet wallet = found.get();
        // 은행 지급에 실패한 인출도 PENDING으로 남아 운영자가 처리하기 전까지 탈퇴를 막는다 (의도한 동작)
        boolean hasPendingTransactions =
                chargeRepository.existsByWallet_IdAndStatusIn(wallet.getId(), IN_PROGRESS_CHARGE_STATUSES)
                        || cashOutRepository.existsByWalletIdAndStatus(wallet.getId(), CashOutStatus.PENDING);
        return WithdrawalCheckResponse.of(wallet.getBalance(), hasPendingTransactions, wallet.getReceivable());
    }
}
