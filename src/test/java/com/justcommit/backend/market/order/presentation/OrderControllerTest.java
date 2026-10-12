package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.order.application.OrderReadService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock OrderService orderService;
    @Mock OrderReadService orderReadService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService, orderReadService))
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

    @Test
    void repaysPendingOrderForAuthenticatedMember() throws Exception {
        when(orderService.repayment(10L, 9L))
                .thenReturn(new OrderCreateResponse(9L, "ORD-test", new BigDecimal("13000"), 1,
                        LocalDateTime.of(2026, 10, 9, 12, 0), OrderStatus.PAID, null));

        mockMvc.perform(post("/api/v1/orders/9/pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        verify(orderService).repayment(10L, 9L);
    }

    @Test
    void rejectsUnauthenticatedRepayment() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/v1/orders/9/pay"))
                .andExpect(status().isUnauthorized());

        verify(orderService, never()).repayment(any(), any());
    }

    @Test
    void listsOnlyAuthenticatedBuyersOrders() throws Exception {
        when(orderReadService.buyerOrders(eq(10L), any(OrderListRequest.class)))
                .thenReturn(new PagingResponse<>(List.of(new OrderResponse(9L, "ORD-test",
                        new BigDecimal("13000"), 1, OrderStatus.PAID, "몬스테라",
                        LocalDateTime.of(2026, 10, 9, 12, 0))), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderId").value(9))
                .andExpect(jsonPath("$.content[0].representativeItemName").value("몬스테라"));

        verify(orderReadService).buyerOrders(eq(10L), any(OrderListRequest.class));
    }

    @Test
    void readsBuyerDetailWithAuthenticatedId() throws Exception {
        when(orderReadService.buyerOrder(10L, 9L))
                .thenReturn(new OrderDetailResponse(9L, "ORD-test", new BigDecimal("13000"), 1,
                        OrderStatus.PAID, LocalDateTime.of(2026, 10, 9, 12, 0), "구매자",
                        "01012345678", "12345", "서울시", null, List.of()));

        mockMvc.perform(get("/api/v1/orders/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("ORD-test"));

        verify(orderReadService).buyerOrder(10L, 9L);
    }

    @Test
    void listsSellerOrdersWithAuthenticatedId() throws Exception {
        when(orderReadService.sellerOrders(eq(10L), any(OrderListRequest.class)))
                .thenReturn(new PagingResponse<>(List.of(new SellerOrderResponse(7L, 9L, "ORD-test",
                        new BigDecimal("13000"), 1, OrderStatus.PAID, "몬스테라",
                        LocalDateTime.of(2026, 10, 9, 12, 0))), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/orders/seller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sellerOrderId").value(7));
    }

    @Test
    void readsSellerDetailWithAuthenticatedId() throws Exception {
        when(orderReadService.sellerOrder(10L, 7L))
                .thenReturn(new SellerOrderDetailResponse(7L, 9L, "ORD-test", new BigDecimal("13000"),
                        new BigDecimal("3000"), OrderStatus.PAID, LocalDateTime.of(2026, 10, 9, 12, 0),
                        "구매자", "01012345678", "12345", "서울시", null, List.of()));

        mockMvc.perform(get("/api/v1/orders/seller/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerOrderId").value(7));

        verify(orderReadService).sellerOrder(10L, 7L);
    }

    @Test
    void rejectsUnauthenticatedOrderLookup() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/orders/seller/7"))
                .andExpect(status().isUnauthorized());

        verify(orderReadService, never()).buyerOrders(any(), any());
        verify(orderReadService, never()).sellerOrder(any(), any());
    }
}
