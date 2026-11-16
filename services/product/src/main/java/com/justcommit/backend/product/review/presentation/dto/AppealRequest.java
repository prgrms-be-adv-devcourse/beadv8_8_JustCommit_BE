package com.justcommit.backend.product.review.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 이의 신청 메모: 필수, 200자 이하 (관리자 확인 목록에 그대로 보임)
public record AppealRequest(
        @NotBlank @Size(max = 200) String memo
) {
}
