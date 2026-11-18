package com.justcommit.backend.member.infrastructure.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

// 회원 정보 HMAC-SHA256 (#413). 같은 값인지 비교하는 데만 쓰고, 원문으로 되돌릴 수 없다
// 입력 앞에 용도 접두사(account: · email: · phone:)를 붙여, 같은 키로 다른 종류의 값을 계산해도 섞이지 않게 한다
@Component
public class MemberHmac {

  private static final String ALGORITHM = "HmacSHA256";
  private static final int KEY_LENGTH_BYTES = 32;

  private final SecretKeySpec key;

  // 키가 없으면 Spring이 placeholder를 못 찾아 실행 실패, 형식·길이가 틀리면 여기서 실행 실패
  public MemberHmac(@Value("${member.hmac-key}") String base64Key) {
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(base64Key);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("member.hmac-key는 Base64 형식이어야 합니다.", e);
    }
    if (keyBytes.length != KEY_LENGTH_BYTES) {
      throw new IllegalStateException("member.hmac-key는 32바이트여야 합니다.");
    }
    this.key = new SecretKeySpec(keyBytes, ALGORITHM);
  }

  // 계좌 중복 확인용 HMAC (hex 64글자)
  // 입력 형식을 은행 코드 3자리·계좌번호 숫자 문자열로 제한하고, 검증된 값을 변환 없이 사용한다
  // (로컬 가입 · 소셜 가입 · 계좌 변경이 같은 규칙)
  public String accountHash(String bankCode, String accountNo) {
    return hmac("account:" + bankCode + ":" + accountNo, "계좌");
  }

  // 탈퇴 기록 · 재가입 제한용 이메일 HMAC (#407). 가입 · 로그인과 같은 규칙(앞뒤 공백 제거 + 소문자)으로 여기서 정규화한다
  // 이미 정규화된 값을 넣어도 결과가 같아, 탈퇴(저장)와 가입(조회)이 같은 값을 만든다
  public String emailHash(String email) {
    return hmac("email:" + email.trim().toLowerCase(Locale.ROOT), "이메일");
  }

  // 탈퇴 기록 · 재가입 제한용 휴대폰 HMAC (#407). 요청 검증에서 숫자 11자리로 제한하므로 변환 없이 사용한다
  public String phoneHash(String phone) {
    return hmac("phone:" + phone, "휴대폰");
  }

  private String hmac(String value, String label) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM); // Mac은 여러 요청이 함께 쓸 수 없어 호출마다 새로 만든다
      mac.init(key);
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(label + " HMAC 계산에 실패했습니다.", e);
    }
  }
}
