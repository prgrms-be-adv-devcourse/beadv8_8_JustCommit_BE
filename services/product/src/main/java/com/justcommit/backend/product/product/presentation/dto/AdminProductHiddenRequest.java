package com.justcommit.backend.product.product.presentation.dto;

import jakarta.validation.constraints.NotNull;

// 관리자 강제 숨김 · 해제. 숨길 때(hidden=true)는 reason 필수(200자 이하, 판매자에게 보임), 해제할 때는 reason을 쓰지 않음
public record AdminProductHiddenRequest(
        @NotNull Boolean hidden,
        String reason
) {
}
