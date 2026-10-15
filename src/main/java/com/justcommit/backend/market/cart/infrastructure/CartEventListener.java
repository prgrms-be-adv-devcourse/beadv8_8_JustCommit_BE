package com.justcommit.backend.market.cart.infrastructure;

import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.member.MemberRegisteredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CartEventListener {

    private final CartService cartService;

    @KafkaListener(topics = MemberRegisteredEvent.TOPIC, groupId = "market-cart")
    public void handle(MemberRegisteredEvent event) {
        if (!event.systemAccount()) {
            cartService.createCart(event.memberId());
        }
    }


}
