package com.justcommit.backend.product.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record ImageUploadUrlRequest(
        @NotEmpty @Size(max = 10) List<@Valid ImageFile> files
) {
    public record ImageFile(
            @NotBlank String contentType,
            @NotNull @Positive Long size
    ) {
    }
}