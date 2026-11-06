package com.justcommit.backend.market.cart.presentation;

import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.market.cart.application.CartService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CartControllerTest {

    @Mock CartService cartService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CartController(cartService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthMember(10L, "BUYER"), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsCartForAuthenticatedMemberNotRequestedMemberId() throws Exception {
        when(cartService.find(10L)).thenReturn(new CartResponse(1L, List.of(), BigDecimal.ZERO, BigDecimal.ZERO));

        mockMvc.perform(get("/api/v1/cart").param("memberId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartId").value(1))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalAmount").value(0));

        verify(cartService).find(10L);
    }

    @Test
    void addsProductForAuthenticatedMember() throws Exception {
        when(cartService.addItemToCart(10L, 101L)).thenReturn(7L);

        mockMvc.perform(post("/api/v1/cart/items")
                        .contentType("application/json")
                        .content("{\"productId\":101}"))
                .andExpect(status().isOk())
                .andExpect(content().string("7"));

        verify(cartService).addItemToCart(10L, 101L);
    }

    @Test
    void rejectsMissingProductId() throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
    }

    @Test
    void rejectsNonPositiveProductId() throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .contentType("application/json")
                        .content("{\"productId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
    }

    @Test
    void removesProductOnlyFromAuthenticatedMembersCart() throws Exception {
        mockMvc.perform(delete("/api/v1/cart/items/101").param("memberId", "999"))
                .andExpect(status().isOk());

        verify(cartService).removeItemFromCart(10L, 101L);
    }
}
