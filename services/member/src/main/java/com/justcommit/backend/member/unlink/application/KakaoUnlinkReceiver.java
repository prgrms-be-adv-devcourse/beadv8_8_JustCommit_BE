package com.justcommit.backend.member.unlink.application;

import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import com.justcommit.backend.member.unlink.domain.KakaoUnlinkReceipt;
import com.justcommit.backend.member.unlink.infrastructure.repository.KakaoUnlinkReceiptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 카카오 연결 해제 웹훅 수신 (#422). 요청 값을 검증해 수신 기록만 남기고, 회원 처리는 스케줄러가 한다.
 * - 키가 맞으면 응답은 항상 200 (카카오 문서: 오류에도 200). 회원 처리를 할지는 요청 값 검증으로 따로 정한다
 * - 저장은 요청 안에서 동기로 하고 DB 쿼리 · 잠금 대기를 제한한다. 연결 풀 대기는 제한하지 못한다(문서 「한계」)
 * - 회원번호 · 헤더 값 · 어드민 키는 로그에 남기지 않는다
 */
@Slf4j
@Service
public class KakaoUnlinkReceiver {

  static final String INVALID_APP_ID = "INVALID_APP_ID";
  static final String INVALID_USER_ID = "INVALID_USER_ID";
  static final String UNKNOWN_REFERRER_TYPE = "UNKNOWN_REFERRER_TYPE";

  // 카카오 문서의 referrer_type. 처리 대상인지(자동 탈퇴 · INACTIVE · 기록만)는 처리 단계에서 정한다
  static final Set<String> KNOWN_REFERRER_TYPES = Set.of(
      "ACCOUNT_DELETE", "FORCED_ACCOUNT_DELETE", "UNLINK_FROM_APPS", "UNLINK_FROM_ADMIN", "INCOMPLETE_SIGN_UP");

  private static final Pattern USER_ID = Pattern.compile("\\d{1," + KakaoUnlinkReceipt.PROVIDER_ID_MAX_LENGTH + "}");
  // 수신 저장의 DB 대기 한도 (카카오 3초 응답 안에 끝내기 위해)
  private static final String STATEMENT_TIMEOUT = "SET LOCAL statement_timeout = '1s'";
  private static final String LOCK_TIMEOUT = "SET LOCAL lock_timeout = '1s'";

  private final byte[] expectedAuthorization;
  private final String appId;
  private final KakaoUnlinkReceiptRepository receiptRepository;
  private final TransactionTemplate transactionTemplate;
  private final JdbcTemplate jdbcTemplate;
  private final DatabaseClock databaseClock;

  public KakaoUnlinkReceiver(@Value("${member.kakao.admin-key}") String adminKey,
                             @Value("${member.kakao.app-id}") String appId,
                             KakaoUnlinkReceiptRepository receiptRepository,
                             TransactionTemplate transactionTemplate,
                             JdbcTemplate jdbcTemplate,
                             DatabaseClock databaseClock) {
    this.expectedAuthorization = ("KakaoAK " + adminKey).getBytes(StandardCharsets.UTF_8);
    this.appId = appId;
    this.receiptRepository = receiptRepository;
    this.transactionTemplate = transactionTemplate;
    this.jdbcTemplate = jdbcTemplate;
    this.databaseClock = databaseClock;
    if (appId == null || appId.isBlank()) {
      // 운영은 배포 스크립트가 값을 강제한다. 로컬에서 비어 있으면 모든 웹훅이 기록만 남음
      log.warn("member.kakao.app-id(KAKAO_APP_ID)가 비어 있어 카카오 연결 해제 웹훅을 처리하지 않습니다.");
    }
  }

  // Authorization: KakaoAK {대표 어드민 키}. 상수 시간 비교
  public boolean isAuthorized(String authorization) {
    return authorization != null
        && MessageDigest.isEqual(expectedAuthorization, authorization.getBytes(StandardCharsets.UTF_8));
  }

  // 저장에 실패해도 예외를 밖으로 내지 않는다 (응답은 항상 200, 유실 위험은 문서 「한계」)
  public void receive(String appId, String userId, String referrerType) {
    try {
      transactionTemplate.executeWithoutResult(status -> {
        jdbcTemplate.execute(STATEMENT_TIMEOUT);
        jdbcTemplate.execute(LOCK_TIMEOUT);
        receiptRepository.save(toReceipt(appId, userId, referrerType, databaseClock.now()));
      });
    } catch (RuntimeException e) {
      // 운영 알림 대상. 회원번호 · 요청 값은 남기지 않는다
      log.error("카카오 연결 해제 웹훅 수신 기록 저장 실패: error={}", e.getClass().getSimpleName());
    }
  }

  KakaoUnlinkReceipt toReceipt(String appId, String userId, String referrerType, Instant now) {
    // 숫자 회원번호만 저장하고, 길이를 넘는 사유는 저장하지 않는다 (입력 원문을 그대로 남기지 않기 위해)
    String providerId = userId != null && USER_ID.matcher(userId).matches() ? userId : null;
    String referrer = referrerType != null && referrerType.length() <= KakaoUnlinkReceipt.REFERRER_TYPE_MAX_LENGTH
        ? referrerType : null;
    if (this.appId == null || this.appId.isBlank() || !this.appId.equals(appId)) {
      return KakaoUnlinkReceipt.ignored(providerId, referrer, INVALID_APP_ID, now);
    }
    if (providerId == null) {
      return KakaoUnlinkReceipt.ignored(null, referrer, INVALID_USER_ID, now);
    }
    if (referrer == null || !KNOWN_REFERRER_TYPES.contains(referrer)) {
      return KakaoUnlinkReceipt.ignored(providerId, referrer, UNKNOWN_REFERRER_TYPE, now);
    }
    return KakaoUnlinkReceipt.received(providerId, referrer, now);
  }
}
