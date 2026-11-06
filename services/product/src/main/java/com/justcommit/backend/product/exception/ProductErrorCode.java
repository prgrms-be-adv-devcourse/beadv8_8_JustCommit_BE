package com.justcommit.backend.product.exception;

import com.justcommit.backend.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum
ProductErrorCode implements ErrorCode {

    SPECIES_NOT_FOUND(HttpStatus.NOT_FOUND, "품종을 찾을 수 없습니다."),
    BANNED_SPECIES(HttpStatus.BAD_REQUEST, "판매가 금지된 품종입니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    PRODUCT_HIDDEN(HttpStatus.FORBIDDEN, "판매자가 비공개 처리한 상품입니다."),
    NOT_SELLER(HttpStatus.FORBIDDEN, "판매자만 상품을 등록할 수 있습니다."),
    NOT_PRODUCT_OWNER(HttpStatus.FORBIDDEN, "본인이 등록한 상품만 변경할 수 있습니다."),
    PRODUCT_NOT_EDITABLE(HttpStatus.CONFLICT, "판매 중인 상품만 수정할 수 있습니다."),
    PRODUCT_NOT_DELETABLE(HttpStatus.CONFLICT, "판매 중인 상품만 삭제할 수 있습니다."),
    PRODUCT_NOT_HIDEABLE(HttpStatus.CONFLICT,  "삭제된 상품은 숨기거나 숨김을 해제할 수 없습니다."),
    INVALID_IMAGE_URL(HttpStatus.BAD_REQUEST, "업로드한 이미지만 등록할 수 있습니다."),
    INVALID_IMAGE_FILE(HttpStatus.BAD_REQUEST, "jpg, png, webp 형식의 10MB 이하 이미지만 업로드할 수 있습니다.");
    private final HttpStatus status;
    private final String message;
}