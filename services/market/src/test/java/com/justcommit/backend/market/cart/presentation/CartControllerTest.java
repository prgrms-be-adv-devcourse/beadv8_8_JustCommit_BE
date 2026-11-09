package com.justcommit.backend.market.cart.presentation;

import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.market.cart.application.CartService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    @DisplayName("가격 동의는 인증 회원으로 처리하고 갱신된 장바구니를 ApiResponse로 감싸 돌려준다")
    void updatesPricesForAuthenticatedMember() throws Exception {
        when(cartService.updatePrices(10L, List.of(new CartItemPriceUpdateRequest.Item(1L, new BigDecimal("12000")))))
                .thenReturn(new CartResponse(1L, List.of(), new BigDecimal("12000"), new BigDecimal("12000")));

        mockMvc.perform(patch("/api/v1/cart/items/prices")
                        .contentType("application/json")
                        .content("{\"items\":[{\"cartItemId\":1,\"expectedPrice\":12000}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("장바구니 가격을 갱신했습니다."))
                .andExpect(jsonPath("$.data.cartId").value(1))
                .andExpect(jsonPath("$.data.nowTotalAmount").value(12000));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"items\":[]}",
            "{\"items\":[null]}",
            "{\"items\":[{\"expectedPrice\":12000}]}",
            "{\"items\":[{\"cartItemId\":1}]}",
            "{\"items\":[{\"cartItemId\":0,\"expectedPrice\":12000}]}",
            "{\"items\":[{\"cartItemId\":1,\"expectedPrice\":-1}]}"})
    @DisplayName("가격 동의 요청의 목록 · ID · 가격이 비었거나 잘못되면 400 INVALID_FIELD_ERROR")
    void rejectsInvalidPriceUpdateRequest(String body) throws Exception {
        mockMvc.perform(patch("/api/v1/cart/items/prices")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));

        verifyNoInteractions(cartService);
    }

    @Test
    void removesProductOnlyFromAuthenticatedMembersCart() throws Exception {
        mockMvc.perform(delete("/api/v1/cart/items/101").param("memberId", "999"))
                .andExpect(status().isOk());

        verify(cartService).removeItemFromCart(10L, 101L);
    }
}
