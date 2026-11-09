package com.justcommit.backend.member.seller.application;

import java.time.LocalDateTime;

public record SellerResult(Long sellerId, String nickname, String intro, LocalDateTime createdAt) {
}
