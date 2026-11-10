package com.justcommit.backend.payment.presentation.internal.dto;

import java.math.BigDecimal;

// 결제와 환불이 함께 쓴다. 환불에서는 orderId를 받기만 하고 쓰지 않는다
public record PaymentRequest(Long orderId, Long buyerId, BigDecimal amount) {
}
