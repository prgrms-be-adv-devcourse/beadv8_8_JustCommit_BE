package com.justcommit.backend.product.domain.exception;

import com.justcommit.backend.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {

    SPECIES_NOT_FOUND(HttpStatus.NOT_FOUND, "품종을 찾을 수 없습니다."),
    BANNED_SPECIES(HttpStatus.BAD_REQUEST, "판매가 금지된 품종입니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    NOT_SELLER(HttpStatus.FORBIDDEN, "판매자만 상품을 등록할 수 있습니다."),
    NOT_PRODUCT_OWNER(HttpStatus.FORBIDDEN, "본인이 등록한 상품만 변경할 수 있습니다."),
    PRODUCT_NOT_EDITABLE(HttpStatus.CONFLICT, "판매 중인 상품만 수정할 수 있습니다."),
    PRODUCT_NOT_DELETABLE(HttpStatus.CONFLICT, "판매 중인 상품만 삭제할 수 있습니다."),
    PRODUCT_NOT_HIDEABLE(HttpStatus.CONFLICT, "판매 중이거나 판매 완료된 상품만 숨길 수 있습니다.");
    private final HttpStatus status;
    private final String message;
}