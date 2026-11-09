package com.justcommit.backend.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Redis 키에 넣을 토큰 해시 (`auth:blacklist:{토큰 해시}` 등). 토큰 원문은 저장하지 않는다.
 * 형식: `Bearer `를 뺀 토큰 문자열 → UTF-8 → SHA-256 → 소문자 16진수 64글자.
 * 쓰는 쪽(member)과 읽는 쪽(Gateway)이 같은 형식이어야 해서 공통 모듈에 둔다.
 */
public final class TokenHash {

  private TokenHash() {
  }

  public static String sha256Hex(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
    }
  }
}
