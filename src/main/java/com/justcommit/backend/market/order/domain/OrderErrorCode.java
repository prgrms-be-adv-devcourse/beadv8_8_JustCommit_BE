package com.justcommit.backend.market.order.domain;

import com.justcommit.backend.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    PRODUCT_UNAVAILABLE(HttpStatus.CONFLICT, "구매할 수 없는 상품이 포함되어 있습니다."),
    OWN_PRODUCT(HttpStatus.BAD_REQUEST, "본인이 판매하는 상품은 구매할 수 없습니다."),
    PRICE_CHANGED(HttpStatus.CONFLICT, "장바구니에 담은 후 상품 가격이 변경되었습니다."),
    CHECKOUT_DATA_MISMATCH(HttpStatus.CONFLICT, "주문에 필요한 정보가 일치하지 않습니다.");

    private final HttpStatus status;
    private final String message;
}
