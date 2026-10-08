package com.justcommit.backend.payment.application;

import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;

}
