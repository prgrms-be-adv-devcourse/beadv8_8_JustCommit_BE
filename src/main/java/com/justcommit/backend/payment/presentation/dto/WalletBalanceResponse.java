package com.justcommit.backend.payment.presentation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@AllArgsConstructor
@Getter
public class WalletBalanceResponse {
    private final BigDecimal balance;
}
