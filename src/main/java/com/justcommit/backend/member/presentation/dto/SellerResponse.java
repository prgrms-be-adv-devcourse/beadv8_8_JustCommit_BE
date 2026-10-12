package com.justcommit.backend.member.presentation.dto;

import com.justcommit.backend.member.application.SellerResult;

import java.time.LocalDateTime;

public record SellerResponse (Long sellerId, String nickname, String intro, LocalDateTime createdAt) {

  public static SellerResponse from(SellerResult result) {
    return new SellerResponse(result.sellerId(), result.nickname(), result.intro(), result.createdAt());
  }
}
