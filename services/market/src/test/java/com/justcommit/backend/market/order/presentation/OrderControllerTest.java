package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrdersItemResult;
import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.order.application.OrderReadService;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.application.OrderService;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    void confirmsItemForAuthenticatedBuyer() throws Exception {
        mockMvc.perform(post("/api/v1/orders/items/11/confirm"))
                .andExpect(status().isOk());

        verify(orderService).confirm(10L, 11L);
    }

    @Test
    void rejectsUnauthenticatedItemConfirmation() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/v1/orders/items/11/confirm"))
                .andExpect(status().isUnauthorized());

        verify(orderService, never()).confirm(any(), any());
    }

    @Test
    void listsOnlyAuthenticatedBuyersOrders() throws Exception {
        when(orderReadService.buyerOrders(eq(10L), any(OrderListRequest.class)))
                .thenReturn(new PagingResponse<>(List.of(new OrderResponse(9L, "ORD-test",
                        new BigDecimal("13000"), 1, OrderStatus.PAID, "몬스테라", "https://img/monstera.jpg",
                        LocalDateTime.of(2026, 10, 9, 12, 0))), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderId").value(9))
                .andExpect(jsonPath("$.content[0].representativeItemName").value("몬스테라"))
                .andExpect(jsonPath("$.content[0].thumbnailUrl").value("https://img/monstera.jpg"));

        verify(orderReadService).buyerOrders(eq(10L), any(OrderListRequest.class));
    }

    @Test
    void readsBuyerDetailWithAuthenticatedId() throws Exception {
        when(orderReadService.buyerOrder(10L, 9L))
                .thenReturn(new OrderDetailResponse(9L, "ORD-test", new BigDecimal("13000"), 1,
                        OrderStatus.PAID, LocalDateTime.of(2026, 10, 9, 12, 0), "구매자",
                        "01012345678", "12345", "서울시", null,
                        List.of(new OrderDetailResponse.SellerOrderDetail(7L, 20L,
                                List.of(OrderDetailResponse.OrderItemDetail.of(new OrdersItemResult(5L, 101L, "몬스테라",
                                        new BigDecimal("10000"), OrderItemStatus.NORMAL), true,
                                        "https://img/monstera.jpg", null)),
                                new BigDecimal("10000"), null, new BigDecimal("3000"),
                                30L, "DELIVERED", LocalDateTime.of(2026, 10, 12, 10, 0),
                                LocalDateTime.of(2026, 10, 12, 10, 3), LocalDateTime.of(2026, 10, 12, 10, 5),
                                LocalDateTime.of(2026, 10, 12, 13, 0)))));

        mockMvc.perform(get("/api/v1/orders/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("ORD-test"))
                .andExpect(jsonPath("$.sellerOrders[0].shipmentStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.sellerOrders[0].items[0].purchaseConfirmable").value(true));

        verify(orderReadService).buyerOrder(10L, 9L);
    }

    @Test
    void listsSellerOrdersWithAuthenticatedId() throws Exception {
        when(orderReadService.sellerOrders(eq(10L), any(OrderListRequest.class)))
                .thenReturn(new PagingResponse<>(List.of(new SellerOrderResponse(7L, 9L, "ORD-test",
                        new BigDecimal("13000"), LocalDateTime.of(2026, 10, 20, 12, 0),
                        1, OrderStatus.PAID, "몬스테라",
                        LocalDateTime.of(2026, 10, 9, 12, 0))), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/orders/seller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sellerOrderId").value(7))
                .andExpect(jsonPath("$.content[0].confirmedAt").value("2026-10-20T12:00:00"))
                .andExpect(jsonPath("$.content[0].returnStatus").hasJsonPath()) // 반품 없으면 null (#626)
                .andExpect(jsonPath("$.content[0].returnItemCount").value(0));
    }

    @Test
    void readsSellerDetailWithAuthenticatedId() throws Exception {
        when(orderReadService.sellerOrder(10L, 7L))
                .thenReturn(new SellerOrderDetailResponse(7L, 9L, "ORD-test", new BigDecimal("13000"),
                        new BigDecimal("3000"), OrderStatus.PAID, LocalDateTime.of(2026, 10, 9, 12, 0),
                        "구매자", "01012345678", "12345", "서울시", null, List.of()));

        mockMvc.perform(get("/api/v1/orders/seller/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerOrderId").value(7))
                .andExpect(jsonPath("$.buyerNickname").hasJsonPath()) // 응답에 칸이 있음 (#599)
                .andExpect(jsonPath("$.confirmedAt").hasJsonPath()); // 확정 전에도 칸은 있음 (#626)

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

    @Test
    void requestsReturnForAuthenticatedBuyer() throws Exception {
        mockMvc.perform(post("/api/v1/orders/items/11/return")
                        .contentType("application/json")
                        .content("{\"reason\":\"DAMAGED\",\"reasonDetail\":\"잎이 부러져 왔어요\"}"))
                .andExpect(status().isOk());

        // 기타가 아닌 사유의 직접 작성 내용도 버리지 않고 넘긴다
        verify(orderService).requestReturn(10L, 11L, ReturnReason.DAMAGED, "잎이 부러져 왔어요");
    }

    @Test
    void returnWithOtherReasonRequiresReasonDetail() throws Exception {
        mockMvc.perform(post("/api/v1/orders/items/11/return")
                        .contentType("application/json")
                        .content("{\"reason\":\"OTHER\",\"reasonDetail\":\"  \"}"))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).requestReturn(any(), any(), any(), any());
    }

    @Test
    void returnRequiresReason() throws Exception {
        mockMvc.perform(post("/api/v1/orders/items/11/return")
                        .contentType("application/json")
                        .content("{\"reasonDetail\":\"사유 없음\"}"))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).requestReturn(any(), any(), any(), any());
    }

    @Test
    void returnReasonDetailIsLimitedTo500CharactersForEveryReason() throws Exception {
        String tooLong = "가".repeat(501);
        mockMvc.perform(post("/api/v1/orders/items/11/return")
                        .contentType("application/json")
                        .content("{\"reason\":\"NOT_AS_DESCRIBED\",\"reasonDetail\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/orders/items/11/return")
                        .contentType("application/json")
                        .content("{\"reason\":\"OTHER\",\"reasonDetail\":\"" + "가".repeat(500) + "\"}"))
                .andExpect(status().isOk());

        verify(orderService).requestReturn(10L, 11L, ReturnReason.OTHER, "가".repeat(500));
    }

    @Test
    void returnRequiresAuthentication() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/v1/orders/items/11/return")
                        .contentType("application/json")
                        .content("{\"reason\":\"DAMAGED\"}"))
                .andExpect(status().isUnauthorized());

        verify(orderService, never()).requestReturn(any(), any(), any(), any());
    }

    @Test
    @DisplayName("판매자 반품 승인, 거절은 본문 없이 200이고 로그인한 회원 id와 주문상품 id를 넘긴다 (#383)")
    void approvesAndRejectsReturn() throws Exception {
        mockMvc.perform(post("/api/v1/orders/items/11/return/approve"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
        mockMvc.perform(post("/api/v1/orders/items/12/return/reject"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(orderService).approveReturn(10L, 11L);
        verify(orderService).rejectReturn(10L, 12L);
    }

    @Test
    @DisplayName("판매자 반품 승인, 거절이 처리되지 않으면 409 RETURN_NOT_DECIDABLE (#383)")
    void returnDecisionConflict() throws Exception {
        doThrow(new BusinessException(OrderErrorCode.RETURN_NOT_DECIDABLE)).when(orderService).approveReturn(10L, 11L);

        mockMvc.perform(post("/api/v1/orders/items/11/return/approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETURN_NOT_DECIDABLE"));
    }
}
