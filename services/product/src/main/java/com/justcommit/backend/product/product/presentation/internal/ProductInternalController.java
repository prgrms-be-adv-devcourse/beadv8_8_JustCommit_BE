package com.justcommit.backend.product.product.presentation.internal;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.product.ProductQuery;
import com.justcommit.backend.product.ProductResult;
import com.justcommit.backend.product.ProductUseCase;
import com.justcommit.backend.product.product.presentation.internal.dto.ProductReservationRequest;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 서비스 간 내부 API (Gateway가 라우팅하지 않음). 규칙 → docs/reference/internal-apis.md
@RestController
@RequestMapping("/internal/v1/products")
@RequiredArgsConstructor
public class ProductInternalController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ProductQuery productQuery;
    private final ProductUseCase productUseCase;

    // market(장바구니 · 주문서) · ai가 상품 정보 · 가격 · 판매 상태를 한 번에 조회. 없는 ID는 결과에서 빠짐
    @GetMapping
    public ApiResponse<List<ProductResult>> getProducts(@RequestParam List<Long> ids) {
        return ApiResponse.ok("상품을 조회했습니다.", productQuery.getProducts(ids.stream().distinct().toList()));
    }

    // 주문 생성 때 상품 예약. 전부 예약되거나 아무것도 예약되지 않음, 같은 orderId로 다시 오면 그대로 성공
    @PostMapping("/reservations")
    public ApiResponse<Void> reserve(
            // 없을 때도 400으로 답하려고 required = false로 받고 직접 확인 (공통 예외 처리기는 헤더 누락을 500으로 바꿈)
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) Long idempotencyKey,
            @RequestBody @Valid ProductReservationRequest request) {

        if (!request.orderId().equals(idempotencyKey)) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST_DATA, "Idempotency-Key는 orderId와 같아야 합니다.");
        }
        productUseCase.reserve(request.orderId(), toExpectedPrices(request.items()));
        return ApiResponse.ok("상품을 예약했습니다.");
    }

    private static Map<Long, BigDecimal> toExpectedPrices(List<ProductReservationRequest.Item> items) {
        Map<Long, BigDecimal> expectedPrices = new LinkedHashMap<>();
        for (ProductReservationRequest.Item item : items) {
            if (expectedPrices.put(item.productId(), item.expectedPrice()) != null) {
                throw new BusinessException(CommonErrorCode.BAD_REQUEST_DATA, "같은 상품을 두 번 보낼 수 없습니다.");
            }
        }
        return expectedPrices;
    }
}
