package com.justcommit.backend.product.presentation.dto;

import jakarta.validation.constraints.NotNull;

public record ProductHiddenRequest (
        @NotNull Boolean hidden
){
}
