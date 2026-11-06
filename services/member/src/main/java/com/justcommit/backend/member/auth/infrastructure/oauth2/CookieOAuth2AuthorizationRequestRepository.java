package com.justcommit.backend.common.security.oauth2;

import com.justcommit.backend.common.security.jwt.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

// OAuth2 인가 요청(state 포함)을 세션 대신 서명된 쿠키에 저장 (STATELESS 유지)
// 쿠키 값 = Base64url(직렬화한 인가 요청) + "." + Base64url(HMAC-SHA256 서명)
@Component
public class CookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

  static final String COOKIE_NAME = "oauth2_auth_request";
  static final Duration COOKIE_MAX_AGE = Duration.ofMinutes(5);

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String KEY_PURPOSE = "oauth2-authorization-request-cookie";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final SecretKeySpec signingKey;
  private final boolean secure;

  public CookieOAuth2AuthorizationRequestRepository(
          JwtProperties jwtProperties,
          @Value("${custom.cookie.secure}") boolean secure) {
    this.signingKey = deriveKey(jwtProperties.secret());
    this.secure = secure;
  }

  // 콜백 때 쿠키에서 인가 요청을 꺼냄. 쿠키가 없거나 위조면 null → Spring이 로그인 실패 처리
  @Override
  public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
    Cookie cookie = WebUtils.getCookie(request, COOKIE_NAME);
    if (cookie == null) {
      return null;
    }
    return decode(cookie.getValue());
  }

  // 로그인 시작 때 인가 요청을 쿠키에 저장
  @Override
  public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
    if (authorizationRequest == null) {
      deleteCookie(response);
      return;
    }
    addCookie(response, encode(authorizationRequest), COOKIE_MAX_AGE);
  }

  // 콜백 처리 시 꺼내면서 쿠키 삭제 → 1번만 사용
  @Override
  public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                               HttpServletResponse response) {
    OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
    deleteCookie(response);
    return authorizationRequest;
  }

  private String encode(OAuth2AuthorizationRequest authorizationRequest) {
    String payload = ENCODER.encodeToString(serialize(authorizationRequest));
    return payload + "." + sign(payload);
  }

  // 서명이 맞을 때만 역직렬화
  private OAuth2AuthorizationRequest decode(String value) {
    int dot = value.lastIndexOf('.');
    if (dot <= 0) {
      return null;
    }
    String payload = value.substring(0, dot);
    String signature = value.substring(dot + 1);
    if (!MessageDigest.isEqual(
            sign(payload).getBytes(StandardCharsets.US_ASCII),
            signature.getBytes(StandardCharsets.US_ASCII))) {
      return null;
    }
    try {
      return deserialize(DECODER.decode(payload));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private String sign(String payload) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(signingKey);
      return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("인가 요청 쿠키 서명 실패", e);
    }
  }

  // jwt.secret에서 쿠키 서명 전용 키를 파생 (JWT 서명 키와 분리)
  private static SecretKeySpec deriveKey(String secret) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      byte[] derived = mac.doFinal(KEY_PURPOSE.getBytes(StandardCharsets.UTF_8));
      return new SecretKeySpec(derived, HMAC_ALGORITHM);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("인가 요청 쿠키 서명 키 생성 실패", e);
    }
  }

  private static byte[] serialize(OAuth2AuthorizationRequest authorizationRequest) {
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
         ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(authorizationRequest);
      out.flush();
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("인가 요청 직렬화 실패", e);
    }
  }

  private static OAuth2AuthorizationRequest deserialize(byte[] data) {
    try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
      Object object = in.readObject();
      return (object instanceof OAuth2AuthorizationRequest request) ? request : null;
    } catch (IOException | ClassNotFoundException e) {
      return null;
    }
  }

  private void addCookie(HttpServletResponse response, String value, Duration maxAge) {
    ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/")
            .maxAge(maxAge)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  private void deleteCookie(HttpServletResponse response) {
    addCookie(response, "", Duration.ZERO);
  }
}
