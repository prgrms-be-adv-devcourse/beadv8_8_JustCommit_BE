package com.justcommit.backend.payment.presentation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class WalletBalanceResponse {
    private final Long balance;
}
