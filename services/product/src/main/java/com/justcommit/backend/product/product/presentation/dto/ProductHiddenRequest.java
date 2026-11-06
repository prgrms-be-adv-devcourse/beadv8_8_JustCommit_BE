package com.justcommit.backend.product.product.presentation.dto;

import jakarta.validation.constraints.NotNull;

public record ProductHiddenRequest (
        @NotNull Boolean hidden
){
}
