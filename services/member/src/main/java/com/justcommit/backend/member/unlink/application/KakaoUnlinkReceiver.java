package com.justcommit.backend.member.unlink.application;

import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import com.justcommit.backend.member.unlink.domain.KakaoUnlinkReceipt;
import com.justcommit.backend.member.unlink.domain.KakaoUnlinkReferrer;
import com.justcommit.backend.member.unlink.infrastructure.metrics.KakaoUnlinkMetrics;
import com.justcommit.backend.member.unlink.infrastructure.repository.KakaoUnlinkReceiptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 카카오 연결 해제 웹훅 수신 (#422). 요청 값을 검증해 수신 기록만 남기고, 회원 처리는 스케줄러가 한다.
 * - 키가 맞으면 응답은 항상 200 (카카오 문서: 오류에도 200). 회원 처리를 할지는 요청 값 검증으로 따로 정한다
 * - 저장은 요청 안에서 동기로 하고 DB 쿼리 · 잠금 대기를 제한한다. 연결 풀 대기는 제한하지 못한다(문서 「한계」)
 * - 저장 실패는 200이라 5xx 알림에 안 잡혀서 지표(KakaoUnlinkMetrics)로 알리고, 재전송이 없어 복구 단서 로그를 남긴다
 * - 헤더 값 · 어드민 키 · 요청 원문 · 예외 메시지는 로그에 남기지 않는다. 카카오 회원번호는 저장 실패 복구 로그에만,
 *   검증을 통과한 값으로 남긴다 (docs/infra/observability.md 「로그」)
 */
@Slf4j
@Service
public class KakaoUnlinkReceiver {

  static final String INVALID_APP_ID = "INVALID_APP_ID";
  static final String INVALID_USER_ID = "INVALID_USER_ID";
  static final String UNKNOWN_REFERRER_TYPE = "UNKNOWN_REFERRER_TYPE";

  // 저장 실패 복구 로그 (Loki에서 event로 찾음)
  static final String RECEIVE_FAILED_EVENT = "KAKAO_UNLINK_RECEIVE_FAILED";
  // 검증을 통과하지 못한 값 대신 남기는 표시 (입력 원문을 로그에 남기지 않기 위해)
  static final String INVALID = "invalid";

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
  private final KakaoUnlinkMetrics metrics;

  public KakaoUnlinkReceiver(@Value("${member.kakao.admin-key}") String adminKey,
                             @Value("${member.kakao.app-id}") String appId,
                             KakaoUnlinkReceiptRepository receiptRepository,
                             TransactionTemplate transactionTemplate,
                             JdbcTemplate jdbcTemplate,
                             DatabaseClock databaseClock,
                             KakaoUnlinkMetrics metrics) {
    this.expectedAuthorization = ("KakaoAK " + adminKey).getBytes(StandardCharsets.UTF_8);
    this.appId = appId;
    this.receiptRepository = receiptRepository;
    this.transactionTemplate = transactionTemplate;
    this.jdbcTemplate = jdbcTemplate;
    this.databaseClock = databaseClock;
    this.metrics = metrics;
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
    // 로그용 수신 시각은 DB를 거치기 전에 앱 시계(UTC)로 잡는다. 저장 실패의 원인이 DB면 DB 시각도 못 읽기 때문
    Instant receivedAt = Instant.now();
    Validation validation = validate(appId, userId, referrerType);
    try {
      transactionTemplate.executeWithoutResult(status -> {
        jdbcTemplate.execute(STATEMENT_TIMEOUT);
        jdbcTemplate.execute(LOCK_TIMEOUT);
        receiptRepository.save(validation.toReceipt(databaseClock.now()));
      });
    } catch (RuntimeException e) {
      metrics.receiveFailed();
      // 복구 단서: 카카오는 다시 보내지 않는다. 자동 복구가 아니며, 복원은 이력을 확인한 뒤 사람이 판단한다
      // (흐름 문서 「운영 알림과 대응」). validation이 IGNORED였던 요청은 복원 대상이 아니다
      log.atError()
          .addKeyValue("event", RECEIVE_FAILED_EVENT)
          .addKeyValue("kakaoUserId", validation.providerId() == null ? INVALID : validation.providerId())
          .addKeyValue("referrerType", validation.knownReferrer() ? validation.referrerType() : INVALID)
          .addKeyValue("receivedAt", receivedAt.toString())
          .addKeyValue("validation", validation.result())
          .addKeyValue("errorCode", "RECEIPT_SAVE_FAILED")
          .addKeyValue("errorType", e.getClass().getSimpleName())
          .log("카카오 연결 해제 웹훅 수신 기록 저장 실패");
    }
  }

  // 요청 값 검증 결과. DB에 접근하기 전에 정해, 저장에 실패해도 무엇으로 저장하려 했는지 로그에 남긴다
  record Validation(String providerId, String referrerType, String ignoreCode) {

    KakaoUnlinkReceipt toReceipt(Instant now) {
      return ignoreCode == null
          ? KakaoUnlinkReceipt.received(providerId, referrerType, now)
          : KakaoUnlinkReceipt.ignored(providerId, referrerType, ignoreCode, now);
    }

    // 알려진 사유인지 (처리 대상인지는 처리 단계에서 정한다)
    boolean knownReferrer() {
      return KakaoUnlinkReferrer.from(referrerType).isPresent();
    }

    // PENDING(정상 처리 대상) 또는 IGNORED:<오류 코드>(처리 제외 대상)
    String result() {
      return ignoreCode == null ? "PENDING" : "IGNORED:" + ignoreCode;
    }
  }

  Validation validate(String appId, String userId, String referrerType) {
    // 숫자 회원번호만 남기고, 길이를 넘는 사유는 남기지 않는다 (입력 원문을 그대로 남기지 않기 위해)
    String providerId = userId != null && USER_ID.matcher(userId).matches() ? userId : null;
    String referrer = referrerType != null && referrerType.length() <= KakaoUnlinkReceipt.REFERRER_TYPE_MAX_LENGTH
        ? referrerType : null;
    if (this.appId == null || this.appId.isBlank() || !this.appId.equals(appId)) {
      return new Validation(providerId, referrer, INVALID_APP_ID);
    }
    if (providerId == null) {
      return new Validation(null, referrer, INVALID_USER_ID);
    }
    if (KakaoUnlinkReferrer.from(referrer).isEmpty()) {
      return new Validation(providerId, referrer, UNKNOWN_REFERRER_TYPE);
    }
    return new Validation(providerId, referrer, null);
  }
}
