package com.justcommit.backend.payment.client.member;

import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.client.exception.InternalApiException;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 인출 신청 때 member에서 계좌를 받는다. 트랜잭션 밖에서만 부른다
// - 404(없는 회원, 탈퇴 회원) → CASH_OUT_ACCOUNT_NOT_FOUND
// - 연결 실패, 타임아웃, 그 밖의 에러 응답, 빈 계좌 → ACCOUNT_LOOKUP_FAILED
// 조회만 하므로 결과 불명도 실패로 보고 거절한다 (돈은 아직 움직이지 않음)
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberAccountClient {

    private final MemberInternalApi memberInternalApi;

    public MemberAccount getAccount(Long memberId) {
        ApiResponse<MemberAccount> response;
        try {
            response = memberInternalApi.getAccount(memberId);
        } catch (InternalApiErrorException e) {
            if (e.status() == 404) {
                throw new BusinessException(PaymentErrorCode.CASH_OUT_ACCOUNT_NOT_FOUND);
            }
            log.warn("계좌 조회 실패: memberId={}, status={}, code={}", memberId, e.status(), e.code());
            throw new BusinessException(PaymentErrorCode.ACCOUNT_LOOKUP_FAILED);
        } catch (InternalApiException e) {
            log.warn("계좌 조회 실패: memberId={}, cause={}", memberId, e.getClass().getSimpleName());
            throw new BusinessException(PaymentErrorCode.ACCOUNT_LOOKUP_FAILED);
        }

        MemberAccount account = response != null ? response.data() : null;
        if (account == null || isBlank(account.bankCode()) || isBlank(account.accountNo())
                || isBlank(account.accountHolder())) {
            log.warn("계좌 조회 응답에 빈 칸이 있음: memberId={}", memberId);
            throw new BusinessException(PaymentErrorCode.ACCOUNT_LOOKUP_FAILED);
        }
        return account;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
