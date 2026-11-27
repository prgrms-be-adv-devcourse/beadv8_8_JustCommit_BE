package com.justcommit.backend.member.member.application;

import com.justcommit.backend.member.support.MemberIntegrationTest;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.member.auth.application.LoginCommand;
import com.justcommit.backend.member.auth.application.LoginResult;
import com.justcommit.backend.member.auth.application.LoginService;
import com.justcommit.backend.member.auth.application.TokenReissueService;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 비밀번호 변경 (#411)을 실제 PostgreSQL · Redis · HTTP로 확인한다.
 * - 이전 Access의 401은 Gateway가 판단하므로, 여기서는 Gateway가 거절할 값(iat <= Redis 폐기 시각)이 기록됐는지까지 본다
 *   (Gateway 쪽 401 처리는 S-A08)
 * - 준비 데이터는 요청 전에 커밋해야 해서 @Transactional을 붙이지 않는다
 */
class PasswordChangeIntegrationTest extends MemberIntegrationTest {

  private static final String OLD_PASSWORD = "plant1234";
  private static final String NEW_PASSWORD = "green5678";
  private static final AtomicInteger SEQ = new AtomicInteger();

  @Value("${local.server.port}")
  private int port;

  @Autowired PasswordChangeService passwordChangeService;
  @Autowired LoginService loginService;
  @Autowired TokenReissueService tokenReissueService;
  @Autowired JwtVerifier jwtVerifier;
  @Autowired MemberRepository memberRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired StringRedisTemplate redisTemplate;
  @Autowired JdbcTemplate jdbcTemplate;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from member_token_revocation");
    jdbcTemplate.update("delete from outbox_event");
    jdbcTemplate.update("delete from seller");
    jdbcTemplate.update("delete from address");
    jdbcTemplate.update("delete from member");
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  @Test
  @DisplayName("HTTP: 바꾸면 200 · revocationCompleted true · 이 기기의 Refresh 쿠키 만료")
  void changePasswordOverHttp() throws Exception {
    Member member = saveMember();
    LoginResult login = login(member, OLD_PASSWORD);

    HttpResponse<String> response = patchPassword(login.accessToken(), OLD_PASSWORD, NEW_PASSWORD);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"revocationCompleted\":true");
    List<String> cookies = response.headers().allValues("set-cookie");
    assertThat(cookies).anySatisfy(cookie -> assertThat(cookie).startsWith("refreshToken=").contains("Max-Age=0"));
  }

  @Test
  @DisplayName("HTTP: Redis 폐기 기록에 실패해도 200 · revocationCompleted false · 지연 안내 메시지 · Refresh 쿠키 만료")
  void pendingOverHttp() throws Exception {
    Member member = saveMember();
    LoginResult login = login(member, OLD_PASSWORD);
    doThrow(new RedisConnectionFailureException("redis down"))
        .when(revokedMemberRedisStore).recordCutoff(anyLong(), any(), any());

    HttpResponse<String> response = patchPassword(login.accessToken(), OLD_PASSWORD, NEW_PASSWORD);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("\"revocationCompleted\":false")
        .contains("비밀번호를 변경했습니다. 다른 기기의 로그아웃이 늦어질 수 있습니다.");
    assertThat(response.headers().allValues("set-cookie"))
        .anySatisfy(cookie -> assertThat(cookie).startsWith("refreshToken=").contains("Max-Age=0"));
    assertThat(passwordEncoder.matches(NEW_PASSWORD, passwordOf(member.getId()))).isTrue();
  }

  @Test
  @DisplayName("[S-A08] 바꾼 뒤: 이전 Refresh 재발급 실패, 이전 Access는 iat <= 폐기 시각, 옛 비밀번호 로그인 실패, 새 비밀번호로만 로그인")
  void afterChangeOldTokensAndPasswordRejected() {
    Member member = saveMember();
    LoginResult before = login(member, OLD_PASSWORD);

    PasswordChangeResult result = passwordChangeService.changePassword(member.getId(), OLD_PASSWORD, NEW_PASSWORD);

    assertThat(result.revocationCompleted()).isTrue();
    assertThatThrownBy(() -> tokenReissueService.reissue(before.refreshToken()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN);
    long cutoff = Long.parseLong(redisTemplate.opsForValue().get("auth:revoked-member:" + member.getId()));
    assertThat(jwtVerifier.verifyAccessToken(before.accessToken()).issuedAt()).isLessThanOrEqualTo(cutoff);
    assertThatThrownBy(() -> login(member, OLD_PASSWORD))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.LOGIN_FAILED);
    assertThat(login(member, NEW_PASSWORD).accessToken()).isNotBlank();
  }

  @Test
  @DisplayName("Redis 폐기 기록에 실패하면 revocationCompleted false지만 비밀번호는 바뀐다")
  void pendingRevocationStillChangesPassword() {
    Member member = saveMember();
    doThrow(new RedisConnectionFailureException("redis down"))
        .when(revokedMemberRedisStore).recordCutoff(anyLong(), any(), any());

    PasswordChangeResult result = passwordChangeService.changePassword(member.getId(), OLD_PASSWORD, NEW_PASSWORD);

    assertThat(result.revocationCompleted()).isFalse();
    assertThat(passwordEncoder.matches(NEW_PASSWORD, passwordOf(member.getId()))).isTrue();
  }

  @Test
  @DisplayName("현재 비밀번호가 틀리면 400 INVALID_CURRENT_PASSWORD, 비밀번호 · 인증 버전 · 폐기 작업 모두 그대로")
  void wrongCurrentPassword() {
    Member member = saveMember();

    assertRejectedWithoutChange(member, "wrong1234", NEW_PASSWORD, MemberErrorCode.INVALID_CURRENT_PASSWORD);
  }

  @Test
  @DisplayName("새 비밀번호가 현재와 같으면 400 SAME_AS_CURRENT_PASSWORD, 모두 그대로")
  void sameAsCurrentPassword() {
    Member member = saveMember();

    assertRejectedWithoutChange(member, OLD_PASSWORD, OLD_PASSWORD, MemberErrorCode.SAME_AS_CURRENT_PASSWORD);
  }

  @Test
  @DisplayName("비밀번호가 없는 소셜 회원은 400 PASSWORD_NOT_SET, 모두 그대로")
  void socialMemberRejected() {
    Member member = saveMember();
    jdbcTemplate.update("update member set password = null, provider = 'KAKAO', provider_id = ? where id = ?",
        "kakao-" + member.getId(), member.getId());

    assertThatThrownBy(() -> passwordChangeService.changePassword(member.getId(), OLD_PASSWORD, NEW_PASSWORD))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.PASSWORD_NOT_SET);
    assertNothingChanged(member);
  }

  @Test
  @DisplayName("탈퇴 회원은 404 MEMBER_NOT_FOUND")
  void withdrawnMember() {
    Member member = saveMember();
    jdbcTemplate.update("update member set status = 'DELETED' where id = ?", member.getId());

    assertThatThrownBy(() -> passwordChangeService.changePassword(member.getId(), OLD_PASSWORD, NEW_PASSWORD))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
  }

  private void assertRejectedWithoutChange(Member member, String current, String next, MemberErrorCode expected) {
    String passwordBefore = passwordOf(member.getId());

    assertThatThrownBy(() -> passwordChangeService.changePassword(member.getId(), current, next))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(expected);

    assertThat(passwordOf(member.getId())).isEqualTo(passwordBefore);
    assertNothingChanged(member);
  }

  // 인증 버전 · 폐기 작업 · Redis 폐기 키가 그대로인지
  private void assertNothingChanged(Member member) {
    assertThat(jdbcTemplate.queryForObject("select token_version from member where id = ?", Integer.class, member.getId()))
        .isZero();
    assertThat(jdbcTemplate.queryForObject("select count(*) from member_token_revocation where member_id = ?",
        Long.class, member.getId())).isZero();
    assertThat(redisTemplate.hasKey("auth:revoked-member:" + member.getId())).isFalse();
  }

  private HttpResponse<String> patchPassword(String accessToken, String current, String next) throws Exception {
    String body = "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/users/me/password"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + accessToken)
        .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private LoginResult login(Member member, String password) {
    return loginService.login(new LoginCommand(member.getEmail(), password));
  }

  private String passwordOf(Long memberId) {
    return jdbcTemplate.queryForObject("select password from member where id = ?", String.class, memberId);
  }

  private Member saveMember() {
    int n = SEQ.incrementAndGet();
    return memberRepository.save(Member.createLocal("pw" + n + "@test.com", passwordEncoder.encode(OLD_PASSWORD),
        "비번" + n, String.format("017%08d", n), "004", "v1:test-account", null, "홍길동"));
  }
}
