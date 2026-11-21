package com.justcommit.backend.settlement.application.withdrawal;

import com.justcommit.backend.settlement.domain.SettlementStatus;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import com.justcommit.backend.settlement.presentation.dto.WithdrawalCheckResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

// member가 탈퇴 전에 부르는 판매자 정산 상태 확인 (#441). DB 오류는 잡지 않고 5xx로 보낸다 (member가 Fail-closed로 막음)
// 정산을 잠그지 않는다: 확인과 탈퇴 커밋 사이에 생긴 정산은 payment(#329)와 같이 따로 막지 않는다
// 회원 존재 여부는 확인하지 않는다: 정산 기록이 없는 회원도 차단 없음
@Service
@RequiredArgsConstructor
public class WithdrawalCheckService {

    // 완료되지 않은 정산. 정산금이 0원이거나 음수인 정산도 센다 (음수 정산은 회수 전이라 탈퇴를 막아야 함, 의도한 동작)
    static final Set<SettlementStatus> INCOMPLETE_STATUSES =
            Set.of(SettlementStatus.PENDING, SettlementStatus.FAILED);

    private final SettlementRepository settlementRepository;

    @Transactional(readOnly = true)
    public WithdrawalCheckResponse check(Long memberId) {
        long incompleteSettlementCount =
                settlementRepository.countBySellerIdAndStatusIn(memberId, INCOMPLETE_STATUSES);
        return WithdrawalCheckResponse.of(incompleteSettlementCount);
    }
}
