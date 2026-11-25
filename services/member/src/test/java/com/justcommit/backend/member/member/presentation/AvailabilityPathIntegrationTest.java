package com.justcommit.backend.member.member.presentation;

import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.infrastructure.crypto.MemberHmac;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import com.justcommit.backend.member.withdrawal.domain.MemberWithdrawalRecord;
import com.justcommit.backend.member.withdrawal.infrastructure.repository.MemberWithdrawalRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 닉네임 · 휴대폰 중복 확인 경로를 실제 HTTP로 호출해 member 보안 필터의 공개 경로를 확인한다 (#423).
 * 휴대폰 확인의 응답(형식 오류 400 · 가입된 번호 false · 30일 안에 탈퇴한 번호 409, #611)도 HTTP로 확인한다.
 * Gateway 쪽 공개 경로는 gateway의 JwtAuthenticationIntegrationTest에서 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class AvailabilityPathIntegrationTest {

  // 실행마다 새 RSA 키 쌍 (MemberApplicationTests와 같은 방식)
  private static final KeyPair JWT_KEY_PAIR = newKeyPair();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
    registry.add("jwt.private-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
    registry.add("jwt.public-key", () -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
  }

  @Value("${local.server.port}")
  private int port;

  @Autowired private MemberRepository memberRepository;
  @Autowired private MemberWithdrawalRecordRepository recordRepository;
  @Autowired private MemberHmac memberHmac;
  @Autowired private DatabaseClock databaseClock;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  @DisplayName("닉네임 · 휴대폰 중복 확인 새 경로는 토큰 없이 200이고 사용 가능 여부를 돌려준다")
  void newPathsArePublic() throws Exception {
    HttpResponse<String> nickname = get("/api/v1/users/nicknameAvailability?nickname=green");
    HttpResponse<String> phone = get("/api/v1/users/phoneAvailability?phone=01012345678");

    assertThat(nickname.statusCode()).isEqualTo(200);
    assertThat(nickname.body()).contains("\"available\":true");
    assertThat(phone.statusCode()).isEqualTo(200);
    assertThat(phone.body()).contains("\"available\":true");
  }

  @Test
  @DisplayName("이미 가입된 휴대폰 번호는 그대로 200 available: false")
  void registeredPhoneIsUnavailable() throws Exception {
    String phone = "01890000001";
    memberRepository.save(Member.createLocal(
        "availability-taken@test.com", "encoded-password", "가입번호", phone, "004", "v1:test-account", null, "홍길동"));

    HttpResponse<String> response = get("/api/v1/users/phoneAvailability?phone=" + phone);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"available\":false");
  }

  @Test
  @DisplayName("30일 안에 탈퇴한 휴대폰 번호는 토큰 없이 409 REJOIN_RESTRICTED_PHONE (#611)")
  void withdrawnPhoneIsRejoinRestricted() throws Exception {
    String phone = "01890000002";
    recordRepository.save(MemberWithdrawalRecord.create(
        memberHmac.emailHash("availability-withdrawn@test.com"), memberHmac.phoneHash(phone), databaseClock.now()));

    HttpResponse<String> response = get("/api/v1/users/phoneAvailability?phone=" + phone);

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.body()).contains("\"code\":\"REJOIN_RESTRICTED_PHONE\"");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"010-1234-5678", "0101234567", "010123456789", "02012345678"})
  @DisplayName("가입 요청과 같은 휴대폰 형식(하이픈 없이 01로 시작하는 11자리)이 아니면 400 INVALID_FIELD_ERROR")
  void invalidPhoneFormatIsBadRequest(String phone) throws Exception {
    HttpResponse<String> response = get("/api/v1/users/phoneAvailability?phone=" + phone);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body())
        .contains("\"code\":\"INVALID_FIELD_ERROR\"")
        .contains("휴대폰 번호는 하이픈 없이 숫자 11자리여야 합니다.");
  }

  @Test
  @DisplayName("phone 파라미터가 없거나 비어 있으면 MISSING_PARAMETER가 아니라 400 INVALID_FIELD_ERROR")
  void missingPhoneIsInvalidField() throws Exception {
    HttpResponse<String> missing = get("/api/v1/users/phoneAvailability");
    // 빈 값은 @NotBlank · @Pattern이 함께 실패해 어느 메시지가 오는지 정해져 있지 않으므로 코드만 본다
    HttpResponse<String> empty = get("/api/v1/users/phoneAvailability?phone=");

    assertThat(missing.statusCode()).isEqualTo(400);
    assertThat(missing.body())
        .contains("\"code\":\"INVALID_FIELD_ERROR\"")
        .contains("휴대폰 번호를 입력해주세요.")
        .doesNotContain("MISSING_PARAMETER");
    assertThat(empty.statusCode()).isEqualTo(400);
    assertThat(empty.body()).contains("\"code\":\"INVALID_FIELD_ERROR\"");
  }

  @Test
  @DisplayName("30일 안에 탈퇴한 번호라도 하이픈을 넣으면 탈퇴 기록을 보기 전에 400")
  void withdrawnPhoneWithHyphenIsBadRequest() throws Exception {
    recordRepository.save(MemberWithdrawalRecord.create(
        memberHmac.emailHash("availability-hyphen@test.com"), memberHmac.phoneHash("01890000003"), databaseClock.now()));

    HttpResponse<String> response = get("/api/v1/users/phoneAvailability?phone=018-9000-0003");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_FIELD_ERROR\"");
  }

  @Test
  @DisplayName("옛 경로(checkNickname · checkPhone)는 남지 않아 토큰 없이 401이다")
  void oldPathsAreGone() throws Exception {
    assertThat(get("/api/v1/users/checkNickname?nickname=green").statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/users/checkPhone?phone=01012345678").statusCode()).isEqualTo(401);
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static KeyPair newKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
