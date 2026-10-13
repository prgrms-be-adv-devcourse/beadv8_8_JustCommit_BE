package com.justcommit.backend.payment.presentation.controller;

import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.payment.application.WalletService;
import com.justcommit.backend.payment.domain.RefType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import com.justcommit.backend.payment.presentation.dto.WalletTransactionResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WalletControllerTest {

    private static final Long MEMBER_ID = 10L;
    private static final String URL = "/api/v1/wallet/transactions";

    @Mock WalletService walletService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new WalletController(walletService))
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver(),
                        new PageableHandlerMethodArgumentResolver())  // standalone에서는 Pageable 변환기를 직접 등록해야 함
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthMember(MEMBER_ID, "BUYER"), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void passesNullRefTypeWhenParameterIsOmitted() throws Exception {
        when(walletService.getTransactions(eq(MEMBER_ID), isNull(), any(Pageable.class))).thenReturn(emptyPage());

        mockMvc.perform(get(URL))
                .andExpect(status().isOk());

        verify(walletService).getTransactions(eq(MEMBER_ID), isNull(), any(Pageable.class));
    }

    @Test
    void passesRequestedRefTypeToService() throws Exception {
        when(walletService.getTransactions(eq(MEMBER_ID), eq(RefType.CHARGE), any(Pageable.class)))
                .thenReturn(emptyPage());

        mockMvc.perform(get(URL).param("refType", "CHARGE"))
                .andExpect(status().isOk());

        verify(walletService).getTransactions(eq(MEMBER_ID), eq(RefType.CHARGE), any(Pageable.class));
    }

    @Test
    void rejectsUnknownRefTypeWithBadRequest() throws Exception {
        mockMvc.perform(get(URL).param("refType", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"));

        verifyNoInteractions(walletService);
    }

    @Test
    void sortsByCreatedAtDescendingByDefault() throws Exception {
        when(walletService.getTransactions(eq(MEMBER_ID), isNull(), any(Pageable.class))).thenReturn(emptyPage());

        mockMvc.perform(get(URL))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(walletService).getTransactions(eq(MEMBER_ID), isNull(), captor.capture());
        Sort.Order order = captor.getValue().getSort().getOrderFor("createdAt");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void usesClientSortWhenProvided() throws Exception {
        when(walletService.getTransactions(eq(MEMBER_ID), isNull(), any(Pageable.class))).thenReturn(emptyPage());

        mockMvc.perform(get(URL).param("sort", "amount,asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(walletService).getTransactions(eq(MEMBER_ID), isNull(), captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("amount").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        assertThat(captor.getValue().getSort().getOrderFor("createdAt")).isNull();
    }

    // Page.empty()는 Unpaged라 JSON 직렬화 시 예외가 날 수 있어 실제 PageRequest로 만든다
    private Page<WalletTransactionResponse> emptyPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
    }
}