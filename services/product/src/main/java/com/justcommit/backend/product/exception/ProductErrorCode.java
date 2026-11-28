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
    PRODUCT_NOT_HIDEABLE(HttpStatus.CONFLICT,  "삭제된 상품은 숨기거나 숨김을 해제할 수 없습니다."),
    INVALID_IMAGE_URL(HttpStatus.BAD_REQUEST, "업로드한 이미지만 등록할 수 있습니다."),
    INVALID_IMAGE_FILE(HttpStatus.BAD_REQUEST, "jpg, png, webp 형식의 7.5MB 이하 이미지만 업로드할 수 있습니다."),
    PRODUCT_NOT_RESERVABLE(HttpStatus.CONFLICT, "구매할 수 없는 상품이 있어 예약하지 못했습니다."),
    PRICE_CHANGED(HttpStatus.CONFLICT, "주문서의 가격과 현재 가격이 다른 상품이 있습니다."),
    PRODUCT_NOT_EDITABLE(HttpStatus.CONFLICT, "판매 중 · 검수 중 · 반려된 상품만 수정할 수 있습니다."),
    PRODUCT_NOT_DELETABLE(HttpStatus.CONFLICT, "판매 중 · 검수 중 · 반려된 상품만 삭제할 수 있습니다."),
    REVIEW_PENDING_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "검수 중인 상품은 동시에 5개까지 둘 수 있습니다."),
    DAILY_REGISTER_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "상품은 하루 20건까지 등록할 수 있습니다."),
    DAILY_REVIEW_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "사진 검수는 하루 30회까지 할 수 있습니다."),
    RESERVATION_RELEASED(HttpStatus.CONFLICT, "이미 해제된 주문이라 예약할 수 없습니다."),
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "검수 기록을 찾을 수 없습니다."),
    REVIEW_ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 검수입니다."),
    REVIEW_OUTDATED(HttpStatus.CONFLICT, "그사이 판매자가 상품을 수정하거나 삭제해 처리할 수 없습니다."),
    INVALID_REJECT_REASON(HttpStatus.BAD_REQUEST, "반려 사유를 확인해 주세요. 기타(OTHER)는 내용을 200자 이하로 입력해야 합니다."),
    RESUBMIT_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "재검수는 2번까지 할 수 있습니다. 상품을 삭제하고 새로 등록해 주세요."),
    RESUBMIT_NOT_ALLOWED(HttpStatus.CONFLICT, "확인이 늦어져 반려된 상품만 같은 내용으로 다시 검수를 요청할 수 있습니다."),
    APPEAL_NOT_ALLOWED(HttpStatus.CONFLICT, "자동으로 반려된 상품만 이의 신청할 수 있습니다."),
    APPEAL_ALREADY_USED(HttpStatus.CONFLICT, "이 검수 결과에는 이미 이의 신청을 했습니다."),
    INVALID_SEARCH_STATUS(HttpStatus.BAD_REQUEST, "상품 목록은 ACTIVE · PAYMENT_PENDING · SOLD_OUT 상태로만 거를 수 있습니다."),
    SEARCH_INDEXING_IN_PROGRESS(HttpStatus.CONFLICT, "상품 검색 전체 색인 · 재색인이 이미 진행 중입니다. 끝난 뒤 다시 요청해 주세요."),
    SEARCH_INDEX_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "상품 검색 색인 작업을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
