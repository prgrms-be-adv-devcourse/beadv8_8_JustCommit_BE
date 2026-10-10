package com.justcommit.backend.member.application;

import java.time.LocalDateTime;

public record SellerResult(Long sellerId, String nickname, String intro, LocalDateTime createdAt) {
}
