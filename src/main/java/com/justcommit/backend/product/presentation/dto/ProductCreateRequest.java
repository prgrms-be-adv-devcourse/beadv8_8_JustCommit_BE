package com.justcommit.backend.product.presentation.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

public record ProductCreateRequest(
        @NotNull Long speciesId,
        @NotBlank @Size(max = 100) String title,
        @NotNull String description,
        @NotNull @Positive BigDecimal price,
        @Positive Integer heightCm,
        @Size(max = 20) String potSize,
        @Size(max = 500) String growNote,
        @Size(max = 10) List<@NotBlank String> pictureUrls
        ) {
}
