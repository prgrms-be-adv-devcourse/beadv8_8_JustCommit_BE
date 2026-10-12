package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.order.application.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock OrderService orderService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService))
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
    void createsOrderForAuthenticatedMemberAndSelectedCartItemIds() throws Exception {
        when(orderService.create(eq(10L), any(CartOrderCreateRequest.class)))
                .thenReturn(new OrderCreateResponse(9L, "ORD-AAAAAAAAAAAAAAAAAAAAAA",
                        new BigDecimal("13000"), 2, LocalDateTime.of(2026, 10, 9, 12, 0),
                        OrderStatus.PAYMENT_PENDING, LocalDateTime.of(2026, 10, 9, 12, 10)));

        mockMvc.perform(post("/api/v1/orders/cart").param("memberId", "999")
                        .contentType("application/json")
                        .content("{\"cartItemIds\":[1,2],\"recipientName\":\"구매자\",\"recipientPhone\":\"01012345678\",\"zipcode\":\"12345\",\"address1\":\"서울시\",\"address2\":\"101호\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(9))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-09T12:10:00"));

        ArgumentCaptor<CartOrderCreateRequest> requestCaptor = ArgumentCaptor.forClass(CartOrderCreateRequest.class);
        verify(orderService).create(eq(10L), requestCaptor.capture());
        assertThat(requestCaptor.getValue().cartItemIds()).containsExactly(1L, 2L);
        assertThat(requestCaptor.getValue().recipientName()).isEqualTo("구매자");
        assertThat(requestCaptor.getValue().recipientPhone()).isEqualTo("01012345678");
    }

    @Test
    void rejectsEmptySelection() throws Exception {
        mockMvc.perform(post("/api/v1/orders/cart")
                        .contentType("application/json")
                        .content("{\"cartItemIds\":[],\"recipientName\":\"구매자\",\"recipientPhone\":\"01012345678\",\"zipcode\":\"12345\",\"address1\":\"서울시\",\"address2\":\"101호\"}"))
                .andExpect(status().isBadRequest());
        verify(orderService, never()).create(any(), any());
    }

    @Test
    void rejectsNonPositiveCartItemId() throws Exception {
        mockMvc.perform(post("/api/v1/orders/cart")
                        .contentType("application/json")
                        .content("{\"cartItemIds\":[0],\"recipientName\":\"구매자\",\"recipientPhone\":\"01012345678\",\"zipcode\":\"12345\",\"address1\":\"서울시\",\"address2\":\"101호\"}"))
                .andExpect(status().isBadRequest());
        verify(orderService, never()).create(any(), any());
    }

    @Test
    void rejectsUnauthenticatedRequestEvenWithCurrentPermissiveSecurityConfig() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/v1/orders/cart")
                        .contentType("application/json")
                        .content("{\"cartItemIds\":[1],\"recipientName\":\"구매자\",\"recipientPhone\":\"01012345678\",\"zipcode\":\"12345\",\"address1\":\"서울시\",\"address2\":\"101호\"}"))
                .andExpect(status().isUnauthorized());
        verify(orderService, never()).create(any(), any());
    }

    @Test
    void rejectsMissingRecipientName() throws Exception {
        mockMvc.perform(post("/api/v1/orders/cart")
                        .contentType("application/json")
                        .content("{\"cartItemIds\":[1],\"recipientPhone\":\"01012345678\",\"zipcode\":\"12345\",\"address1\":\"서울시\",\"address2\":\"101호\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
        verify(orderService, never()).create(any(), any());
    }

    @Test
    void rejectsBlankRecipientPhone() throws Exception {
        mockMvc.perform(post("/api/v1/orders/cart")
                        .contentType("application/json")
                        .content("{\"cartItemIds\":[1],\"recipientName\":\"구매자\",\"recipientPhone\":\" \" ,\"zipcode\":\"12345\",\"address1\":\"서울시\",\"address2\":\"101호\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
        verify(orderService, never()).create(any(), any());
    }
}
