package com.justcommit.backend.payment.application;

import com.justcommit.backend.member.MemberQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SystemWalletInitializer {

    private final MemberQuery memberQuery;
    private final WalletService walletService;

    @EventListener(ApplicationReadyEvent.class)
    public void createSystemWallets() {
        try {
            walletService.createIfAbsent(memberQuery.getHoldingMemberId());
            walletService.createIfAbsent(memberQuery.getRevenueMemberId());
        } catch (IllegalStateException e) {
            log.error("시스템 지갑 생성 실패: 시스템 계정이 없음", e);
        }
    }
}