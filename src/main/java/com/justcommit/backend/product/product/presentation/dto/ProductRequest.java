package com.justcommit.backend.product.product.presentation.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

public record ProductRequest(
        @NotNull Long speciesId,
        @NotBlank @Size(max = 100) String title,
        @NotNull String description,
        @NotNull @Positive BigDecimal price,
        @Positive Integer heightCm,
        @Size(max = 20) String potSize,
        @Size(max = 500) String growNote,
        @NotEmpty(message = "사진을 1장 이상 등록해 주세요.")
        @Size(max = 10, message = "사진은 최대 10장까지 등록할 수 있어요.")
        List<@NotBlank String> pictureUrls
        ) {
}
