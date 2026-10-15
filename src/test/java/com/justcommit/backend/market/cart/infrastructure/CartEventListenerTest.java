package com.justcommit.backend.market.cart.infrastructure;

import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.member.MemberRegisteredEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CartEventListenerTest {

    @Mock CartService cartService;
    @InjectMocks CartEventListener listener;

    @Test
    void createsCartForRegisteredMember() {
        listener.handle(new MemberRegisteredEvent(10L, false));

        verify(cartService).createCart(10L);
    }

    @Test
    void skipsSystemAccount() {
        listener.handle(new MemberRegisteredEvent(10L, true));

        verifyNoInteractions(cartService);
    }

    @Test
    void handlesDifferentMembersSeparately() {
        listener.handle(new MemberRegisteredEvent(10L, false));
        listener.handle(new MemberRegisteredEvent(20L, false));

        verify(cartService).createCart(10L);
        verify(cartService).createCart(20L);
    }
}
