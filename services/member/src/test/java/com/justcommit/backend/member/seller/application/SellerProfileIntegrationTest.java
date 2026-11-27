package com.justcommit.backend.member.seller.application;

import com.justcommit.backend.member.support.MemberIntegrationTest;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.seller.domain.SellerReviewConsentPolicy;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판매자 공개 정보 · 소개글 수정 (#417).
 * - 공개 조회와 비로그인 수정 거절은 실제 HTTP로 member 보안 설정까지 확인한다
 * - 본인 수정은 서비스를 직접 부른다 (인증 정보의 회원 ID만 쓰는 것은 컨트롤러가 맡음)
 * - 준비 데이터는 요청 전에 커밋해야 서버가 볼 수 있어 @Transactional을 붙이지 않는다
 */
class SellerProfileIntegrationTest extends MemberIntegrationTest {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Value("${local.server.port}")
  private int port;

  @Autowired SellerProfileService sellerProfileService;
  @Autowired MemberRepository memberRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired MemberLocker memberLocker;
  @Autowired TransactionTemplate transactionTemplate;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from outbox_event");
    jdbcTemplate.update("delete from seller");
    jdbcTemplate.update("delete from address");
    jdbcTemplate.update("delete from member");
  }

  @Test
  @DisplayName("로그인 없이 판매자 공개 정보를 조회할 수 있다")
  void getSellerWithoutLogin() throws Exception {
    Member seller = saveSeller("반가워요 식물 팔아요");

    HttpResponse<String> response = get("/api/v1/sellers/" + seller.getId());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("\"sellerId\":" + seller.getId())
        .contains("\"nickname\":\"" + seller.getNickname() + "\"")
        .contains("\"intro\":\"반가워요 식물 팔아요\"")
        .contains("\"createdAt\"");
  }

  @Test
  @DisplayName("판매자가 아닌 회원 · 없는 회원 · 탈퇴한 판매자는 404 SELLER_NOT_FOUND")
  void getSellerNotFound() throws Exception {
    Member notSeller = saveMember();
    Member withdrawn = saveSeller("탈퇴할 판매자");
    jdbcTemplate.update("update member set status = 'DELETED' where id = ?", withdrawn.getId());

    for (Long id : new Long[] {notSeller.getId(), Long.MAX_VALUE, withdrawn.getId()}) {
      HttpResponse<String> response = get("/api/v1/sellers/" + id);
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.body()).contains("\"code\":\"SELLER_NOT_FOUND\"");
    }
  }

  @Test
  @DisplayName("로그인 없이 소개글을 고치면 401 (sellers/me/intro는 공개 경로가 아님)")
  void changeIntroWithoutLogin() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/sellers/me/intro"))
        .header("Content-Type", "application/json")
        .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"intro\":\"몰래 수정\"}"))
        .build();

    assertThat(httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("로그인 없이 판매자 등록 동의 내용을 조회하면 401 (consent-policy는 공개 경로가 아님)")
  void getConsentPolicyWithoutLogin() throws Exception {
    assertThat(get("/api/v1/sellers/consent-policy").statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("판매자 본인은 소개글을 고칠 수 있고, 응답에 닉네임 · 등록일이 함께 온다")
  void changeIntro() {
    Member seller = saveSeller("처음 소개");

    SellerResult result = sellerProfileService.changeIntro(seller.getId(), "바뀐 소개");

    assertThat(result.sellerId()).isEqualTo(seller.getId());
    assertThat(result.nickname()).isEqualTo(seller.getNickname());
    assertThat(result.intro()).isEqualTo("바뀐 소개");
    assertThat(result.createdAt()).isNotNull();
    assertThat(introOf(seller.getId())).isEqualTo("바뀐 소개");
  }

  @Test
  @DisplayName("빈 문자열에서 바뀐 NULL로 고치면 소개글이 NULL로 저장된다")
  void clearIntro() {
    Member seller = saveSeller("지울 소개");

    SellerResult result = sellerProfileService.changeIntro(seller.getId(), null);

    assertThat(result.intro()).isNull();
    assertThat(introOf(seller.getId())).isNull();
  }

  @Test
  @DisplayName("판매자가 아닌 회원이 고치면 403 NOT_SELLER")
  void changeIntroByNonSeller() {
    Member member = saveMember();

    assertThatThrownBy(() -> sellerProfileService.changeIntro(member.getId(), "판매자 아님"))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.NOT_SELLER);
  }

  @Test
  @DisplayName("탈퇴 회원이 고치면 404 MEMBER_NOT_FOUND")
  void changeIntroByWithdrawnMember() {
    Member seller = saveSeller("탈퇴할 판매자");
    jdbcTemplate.update("update member set status = 'DELETED' where id = ?", seller.getId());

    assertThatThrownBy(() -> sellerProfileService.changeIntro(seller.getId(), "수정 시도"))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
  }

  @Test
  @DisplayName("탈퇴가 회원을 잠근 동안 온 소개글 수정은 기다렸다가 404 MEMBER_NOT_FOUND, 탈퇴가 지운 소개글이 다시 저장되지 않음 (#407)")
  void introChangeDuringWithdrawal() throws Exception {
    Long memberId = saveSeller("원래 소개").getId();
    CountDownLatch anonymized = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      // 탈퇴의 최종 처리처럼: 회원 행을 잠그고 익명화 · 소개글 NULL을 반영한 뒤 커밋 전에 멈춤
      Future<?> withdrawal = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
        memberLocker.lockById(memberId).orElseThrow();
        jdbcTemplate.update("update member set status = 'DELETED', email = null, nickname = null, phone = null where id = ?", memberId);
        jdbcTemplate.update("update seller set intro = null where member_id = ?", memberId);
        anonymized.countDown();
        awaitQuietly(release);
      }));
      assertThat(anonymized.await(10, TimeUnit.SECONDS)).isTrue();

      // 연락처를 넣으려는 소개글 수정: 같은 회원 행 잠금에서 기다려야 한다
      Future<Throwable> change = executor.submit(() -> {
        try {
          sellerProfileService.changeIntro(memberId, "010-1234-5678로 연락 주세요");
          return null;
        } catch (Throwable t) {
          return t;
        }
      });
      waitForLockWaiter();
      release.countDown();
      withdrawal.get(10, TimeUnit.SECONDS);

      assertThat(change.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(BusinessException.class,
          e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
      assertThat(introOf(memberId)).isNull();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  // 다른 트랜잭션이 행 잠금을 기다리기 시작할 때까지 (MemberLocker 대기 제한 3초 안에서 확인)
  private void waitForLockWaiter() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbcTemplate.queryForObject("select count(*) from pg_locks where not granted", Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("소개글 수정이 행 잠금을 기다리지 않음");
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private HttpResponse<String> get(String path) throws Exception {
    return httpClient.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private String introOf(Long sellerId) {
    return jdbcTemplate.queryForObject("select intro from seller where member_id = ?", String.class, sellerId);
  }

  private Member saveSeller(String intro) {
    Member member = saveMember();
    jdbcTemplate.update("update member set role = 'SELLER' where id = ?", member.getId());
    jdbcTemplate.update("insert into seller (member_id, intro, review_consent_version, review_consented_at, created_at, updated_at) values (?, ?, ?, now(), now(), now())",
        member.getId(), intro, SellerReviewConsentPolicy.VERSION);
    return member;
  }

  private Member saveMember() {
    int n = SEQ.incrementAndGet();
    return memberRepository.save(Member.createLocal("seller" + n + "@test.com", passwordEncoder.encode("plant1234"),
        "판매" + n, String.format("016%08d", n), "004", "v1:test-account", null, "홍길동"));
  }
}
