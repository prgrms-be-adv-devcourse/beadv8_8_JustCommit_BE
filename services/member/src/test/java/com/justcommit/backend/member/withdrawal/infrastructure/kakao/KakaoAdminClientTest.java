package com.justcommit.backend.member.withdrawal.infrastructure.kakao;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 카카오 연결 해제 클라이언트 (#407)를 WireMock(카카오 대역)으로 확인한다.
 * 응답 모양은 11/18 로컬에서 실제 카카오로 확인한 결과(성공 200, 이미 끊긴 회원 400 + code -101)에 맞춘다.
 */
class KakaoAdminClientTest {

  private static final String ADMIN_KEY = "test-admin-key";
  private static final String UNLINK = "/v1/user/unlink";
  private static final String USER_ME = "/v2/user/me";

  @RegisterExtension
  static final WireMockExtension KAKAO = WireMockExtension.newInstance()
      .options(wireMockConfig().dynamicPort())
      .build();

  private final KakaoAdminClient client = new KakaoAdminClient(
      KAKAO.baseUrl(), ADMIN_KEY, Duration.ofSeconds(1), Duration.ofMillis(500));

  @Test
  @DisplayName("성공: 어드민 키 헤더와 폼(target_id_type=user_id, target_id=회원번호)으로 부르고, 내부 키는 보내지 않는다")
  void unlink_success() {
    KAKAO.stubFor(post(urlPathEqualTo(UNLINK)).willReturn(okJson("{\"id\":1234567890}")));

    client.unlink("1234567890");

    KAKAO.verify(postRequestedFor(urlPathEqualTo(UNLINK))
        .withHeader("Authorization", equalTo("KakaoAK " + ADMIN_KEY))
        .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
        .withHeader("X-Internal-Key", absent())
        .withRequestBody(containing("target_id_type=user_id"))
        .withRequestBody(containing("target_id=1234567890")));
  }

  @Test
  @DisplayName("이미 끊긴 회원(400 + code -101)은 성공으로 처리")
  void unlink_alreadyUnlinked() {
    KAKAO.stubFor(post(urlPathEqualTo(UNLINK))
        .willReturn(errorJson(400, "{\"msg\":\"NotRegisteredUserException\",\"code\":-101}")));

    assertThatCode(() -> client.unlink("1234567890")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("400이 아닌 상태의 -101(예: 403 + code -101)은 이미 해제로 보지 않고 503 SOCIAL_UNLINK_FAILED")
  void unlink_minus101WithOtherStatus() {
    KAKAO.stubFor(post(urlPathEqualTo(UNLINK))
        .willReturn(errorJson(403, "{\"msg\":\"NotRegisteredUserException\",\"code\":-101}")));

    assertUnlinkFailed();
  }

  @Test
  @DisplayName("-101이 아닌 카카오 오류(예: -2)는 503 SOCIAL_UNLINK_FAILED")
  void unlink_otherKakaoError() {
    KAKAO.stubFor(post(urlPathEqualTo(UNLINK))
        .willReturn(errorJson(400, "{\"msg\":\"invalid argument\",\"code\":-2}")));

    assertUnlinkFailed();
  }

  @Test
  @DisplayName("오류 본문이 JSON이 아니면 성공으로 보지 않고 503 SOCIAL_UNLINK_FAILED")
  void unlink_unparsableBody() {
    KAKAO.stubFor(post(urlPathEqualTo(UNLINK)).willReturn(aResponse().withStatus(400).withBody("not json")));

    assertUnlinkFailed();
  }

  @Test
  @DisplayName("카카오 서버 오류(500)는 503 SOCIAL_UNLINK_FAILED")
  void unlink_serverError() {
    KAKAO.stubFor(post(urlPathEqualTo(UNLINK)).willReturn(aResponse().withStatus(500)));

    assertUnlinkFailed();
  }

  @Test
  @DisplayName("응답 시간 초과는 다시 부르지 않고 503 SOCIAL_UNLINK_FAILED")
  void unlink_timeout() {
    KAKAO.stubFor(post(urlPathEqualTo(UNLINK)).willReturn(okJson("{\"id\":1}").withFixedDelay(1500)));

    assertUnlinkFailed();
    KAKAO.verify(1, postRequestedFor(urlPathEqualTo(UNLINK)));
  }

  @Test
  @DisplayName("어드민 키가 비어 있으면 만들 때 실패 (member가 시작하지 않음)")
  void blankAdminKey() {
    assertThatThrownBy(() -> new KakaoAdminClient(KAKAO.baseUrl(), " ", Duration.ofSeconds(1), Duration.ofSeconds(3)))
        .isInstanceOf(IllegalStateException.class);
  }

  // ===== 연결 상태 조회 (#422) =====

  @Test
  @DisplayName("[#422] 연결 상태 조회: 200이면 연결됨. 어드민 키와 쿼리(target_id_type=user_id, target_id=회원번호)로 부르고 내부 키는 보내지 않는다")
  void linkStatus_linked() {
    KAKAO.stubFor(get(urlPathEqualTo(USER_ME)).willReturn(okJson("{\"id\":1234567890}")));

    assertThat(client.linkStatus("1234567890")).isEqualTo(KakaoAdminClient.LinkStatus.LINKED);

    KAKAO.verify(getRequestedFor(urlPathEqualTo(USER_ME))
        .withHeader("Authorization", equalTo("KakaoAK " + ADMIN_KEY))
        .withHeader("X-Internal-Key", absent())
        .withQueryParam("target_id_type", equalTo("user_id"))
        .withQueryParam("target_id", equalTo("1234567890")));
  }

  @Test
  @DisplayName("[#422] 연결 상태 조회: 400 + code -101이면 끊김")
  void linkStatus_unlinked() {
    KAKAO.stubFor(get(urlPathEqualTo(USER_ME))
        .willReturn(errorJson(400, "{\"msg\":\"NotRegisteredUserException\",\"code\":-101}")));

    assertThat(client.linkStatus("1234567890")).isEqualTo(KakaoAdminClient.LinkStatus.UNLINKED);
  }

  @Test
  @DisplayName("[#422] 연결 상태 조회: 403 + -101 · 다른 카카오 오류 · 본문이 JSON이 아님 · 500 · 시간 초과는 확인 실패(재시도 대상)")
  void linkStatus_unavailable() {
    String minus101 = "{\"msg\":\"NotRegisteredUserException\",\"code\":-101}";
    assertLinkStatusUnavailable(errorJson(403, minus101));
    assertLinkStatusUnavailable(errorJson(400, "{\"msg\":\"InvalidArgument\",\"code\":-2}"));
    assertLinkStatusUnavailable(aResponse().withStatus(400).withBody("not json"));
    assertLinkStatusUnavailable(aResponse().withStatus(500));
    assertLinkStatusUnavailable(okJson("{\"id\":1}").withFixedDelay(1500));
  }

  private void assertLinkStatusUnavailable(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
    KAKAO.resetAll();
    KAKAO.stubFor(get(urlPathEqualTo(USER_ME)).willReturn(response));
    assertThatThrownBy(() -> client.linkStatus("1234567890")).isInstanceOf(KakaoApiUnavailableException.class);
  }

  private void assertUnlinkFailed() {
    assertThatThrownBy(() -> client.unlink("1234567890"))
        .isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.SOCIAL_UNLINK_FAILED));
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(String body) {
    return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder errorJson(int status, String body) {
    return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
  }
}
