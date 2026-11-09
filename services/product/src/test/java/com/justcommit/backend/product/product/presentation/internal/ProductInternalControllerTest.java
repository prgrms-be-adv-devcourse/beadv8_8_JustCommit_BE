package com.justcommit.backend.product.product.presentation.internal;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.product.ProductQuery;
import com.justcommit.backend.product.ProductResult;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.ProductUseCase;
import com.justcommit.backend.product.exception.ProductErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProductInternalControllerTest {

    private static final String RESERVE_BODY = """
            {
              "orderId": 7,
              "items": [
                {"productId": 1, "expectedPrice": 10000},
                {"productId": 2, "expectedPrice": 25000}
              ]
            }
            """;

    @Mock
    private ProductQuery productQuery;

    @Mock
    private ProductUseCase productUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductInternalController(productQuery, productUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("상품 일괄 조회는 같은 ID를 한 번만 넘기고 market이 쓰는 필드로 응답한다")
    void getProducts() throws Exception {
        given(productQuery.getProducts(List.of(1L, 2L))).willReturn(List.of(
                new ProductResult(1L, 9L, "몬스테라", new BigDecimal("10000"), ProductStatus.ACTIVE, false,
                        "https://example.com/a.jpg", "몬스테라")));

        mockMvc.perform(get("/internal/v1/products").param("ids", "1,2,1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].productId").value(1))
                .andExpect(jsonPath("$.data[0].sellerId").value(9))
                .andExpect(jsonPath("$.data[0].price").value(10000))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].hidden").value(false))
                .andExpect(jsonPath("$.data[0].speciesName").value("몬스테라"));
    }

    @Test
    @DisplayName("예약 요청은 상품별 주문서 가격을 그대로 넘기고 200으로 응답한다")
    void reserve() throws Exception {
        mockMvc.perform(post("/internal/v1/products/reservations")
                        .header("Idempotency-Key", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESERVE_BODY))
                .andExpect(status().isOk());

        verify(productUseCase).reserve(7L, Map.of(1L, new BigDecimal("10000"), 2L, new BigDecimal("25000")));
    }

    @Test
    @DisplayName("가격이 바뀌었으면 409 PRICE_CHANGED")
    void reservePriceChanged() throws Exception {
        willThrow(new BusinessException(ProductErrorCode.PRICE_CHANGED))
                .given(productUseCase).reserve(anyLong(), any());

        mockMvc.perform(post("/internal/v1/products/reservations")
                        .header("Idempotency-Key", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESERVE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"));
    }

    @Test
    @DisplayName("Idempotency-Key가 orderId와 다르면 400, 예약하지 않는다")
    void reserveKeyMismatch() throws Exception {
        mockMvc.perform(post("/internal/v1/products/reservations")
                        .header("Idempotency-Key", "8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESERVE_BODY))
                .andExpect(status().isBadRequest());

        verify(productUseCase, never()).reserve(anyLong(), any());
    }

    @Test
    @DisplayName("Idempotency-Key가 없으면 400, 예약하지 않는다")
    void reserveWithoutKey() throws Exception {
        mockMvc.perform(post("/internal/v1/products/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESERVE_BODY))
                .andExpect(status().isBadRequest());

        verify(productUseCase, never()).reserve(anyLong(), any());
    }

    @Test
    @DisplayName("같은 상품을 두 번 보내면 400, 예약하지 않는다")
    void reserveDuplicateProduct() throws Exception {
        mockMvc.perform(post("/internal/v1/products/reservations")
                        .header("Idempotency-Key", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": 7, "items": [
                                  {"productId": 1, "expectedPrice": 10000},
                                  {"productId": 1, "expectedPrice": 10000}
                                ]}
                                """))
                .andExpect(status().isBadRequest());

        verify(productUseCase, never()).reserve(anyLong(), any());
    }

    @Test
    @DisplayName("상품 목록이 비었거나 가격이 없으면 400")
    void reserveInvalidBody() throws Exception {
        mockMvc.perform(post("/internal/v1/products/reservations")
                        .header("Idempotency-Key", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": 7, "items": []}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/internal/v1/products/reservations")
                        .header("Idempotency-Key", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": 7, "items": [{"productId": 1}]}
                                """))
                .andExpect(status().isBadRequest());

        verify(productUseCase, never()).reserve(anyLong(), any());
    }
}
