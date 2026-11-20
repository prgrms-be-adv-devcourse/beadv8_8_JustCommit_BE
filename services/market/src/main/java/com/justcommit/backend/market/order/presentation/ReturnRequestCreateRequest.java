package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.order.domain.ReturnRequest;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 구매자 반품 신청 (#382). 직접 작성 내용은 기타면 필수, 그 밖의 사유면 선택이며 오면 그대로 저장한다
public record ReturnRequestCreateRequest(
        @NotNull ReturnReason reason,
        @Size(max = ReturnRequest.REASON_DETAIL_MAX_LENGTH) String reasonDetail
) {

    @JsonIgnore
    @AssertTrue(message = "기타 사유는 직접 작성 내용이 필요합니다.")
    public boolean isReasonDetailPresentForOther() {
        return reason != ReturnReason.OTHER || (reasonDetail != null && !reasonDetail.isBlank());
    }
}
