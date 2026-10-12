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
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    SELLER_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "판매자 주문을 찾을 수 없습니다."),
    ORDER_NOT_PAYABLE(HttpStatus.CONFLICT, "결제 대기 중인 주문만 결제할 수 있습니다."),
    PAYMENT_WINDOW_EXPIRED(HttpStatus.CONFLICT, "주문 결제 시간이 만료되었습니다."),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "예치금 지갑을 찾을 수 없습니다."),
    CHECKOUT_DATA_MISMATCH(HttpStatus.CONFLICT, "주문에 필요한 정보가 일치하지 않습니다.");

    private final HttpStatus status;
    private final String message;
}
