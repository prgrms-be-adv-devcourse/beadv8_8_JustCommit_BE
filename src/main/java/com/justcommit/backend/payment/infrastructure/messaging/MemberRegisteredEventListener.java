package com.justcommit.backend.payment.infrastructure.messaging;

import com.justcommit.backend.member.MemberRegisteredEvent;
import com.justcommit.backend.payment.application.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class MemberRegisteredEventListener {

    private final WalletService walletService;

    @KafkaListener(
            topics = MemberRegisteredEvent.TOPIC,
            groupId = "payment-wallet",
            properties = "auto.offset.reset=earliest"
    )
    public void onMemberRegistered(MemberRegisteredEvent event) {
        walletService.createIfAbsent(event.memberId());
    }
}