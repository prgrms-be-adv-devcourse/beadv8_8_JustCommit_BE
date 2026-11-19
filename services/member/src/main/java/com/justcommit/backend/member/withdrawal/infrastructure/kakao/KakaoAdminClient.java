package com.justcommit.backend.member.withdrawal.infrastructure.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/**
 * 카카오 어드민 키로 부르는 API (#407). 내부 호출 그룹(X-Internal-Key)을 쓰지 않는 외부 호출 전용 RestClient
 * - 연결 해제: 어드민 키 + 회원번호(DB의 provider_id). 이미 끊긴 회원은 400 + code -101 → 성공으로 처리
 * - 실패는 재시도하지 않고 503 SOCIAL_UNLINK_FAILED (docs/conventions/resilience.md)
 * - 연결 상태 조회(#422 웹훅 처리): 어드민 키 + 회원번호. 200이면 연결됨, 400 + code -101이면 끊김, 그 밖은 KakaoApiUnavailableException
 * - 로그에는 HTTP 상태와 카카오 오류 코드만 남긴다 (어드민 키 · 회원번호 제외)
 */
@Slf4j
@Component
public class KakaoAdminClient {

  private static final String UNLINK_PATH = "/v1/user/unlink";
  private static final String USER_ME_PATH = "/v2/user/me";
  private static final int NOT_REGISTERED_USER = -101; // 연결되지 않은 사용자 (이미 해제됨)

  private final RestClient restClient;

  public KakaoAdminClient(@Value("${member.kakao.api-base-url}") String baseUrl,
                          @Value("${member.kakao.admin-key}") String adminKey,
                          @Value("${member.kakao.connect-timeout}") Duration connectTimeout,
                          @Value("${member.kakao.read-timeout}") Duration readTimeout) {
    // 환경변수가 비어 있으면 placeholder는 빈 문자열로 풀리므로 여기서 막는다 (키의 실제 유효 여부는 호출할 때 알 수 있음)
    if (adminKey == null || adminKey.isBlank()) {
      throw new IllegalStateException("member.kakao.admin-key(KAKAO_ADMIN_KEY)가 비어 있습니다.");
    }
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(connectTimeout);
    factory.setReadTimeout(readTimeout);
    this.restClient = RestClient.builder()
        .requestFactory(factory)
        .baseUrl(baseUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + adminKey)
        .build();
  }

  // 회원의 카카오 연결을 끊는다. 이미 끊겨 있으면(-101) 성공과 같다
  public void unlink(String providerId) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("target_id_type", "user_id");
    form.add("target_id", providerId);
    try {
      restClient.post()
          .uri(UNLINK_PATH)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      Integer kakaoCode = kakaoErrorCode(e);
      // 카카오 오류 계약은 "HTTP 400 + code -101". 다른 상태의 -101은 이미 해제됐다고 볼 근거가 없어 실패로 둔다
      if (e.getStatusCode().value() == 400 && kakaoCode != null && kakaoCode == NOT_REGISTERED_USER) {
        return;
      }
      log.warn("카카오 연결 해제 실패: status={}, kakaoCode={}", e.getStatusCode().value(), kakaoCode);
      throw new BusinessException(MemberErrorCode.SOCIAL_UNLINK_FAILED);
    } catch (ResourceAccessException e) {
      log.warn("카카오 연결 해제 실패: 연결 실패 또는 시간 초과 ({})", e.getClass().getSimpleName());
      throw new BusinessException(MemberErrorCode.SOCIAL_UNLINK_FAILED);
    }
  }

  public enum LinkStatus { LINKED, UNLINKED }

  // 회원의 현재 카카오 연결 상태 (#422). 웹훅이 늦게 오거나 그 사이 재연결했을 수 있어 처리 시점에 다시 확인한다
  // 확인하지 못하면(연결 실패 · 시간 초과 · 그 밖의 응답) 예외 → 호출한 쪽이 재시도
  public LinkStatus linkStatus(String providerId) {
    try {
      restClient.get()
          .uri(uri -> uri.path(USER_ME_PATH)
              .queryParam("target_id_type", "user_id")
              .queryParam("target_id", providerId)
              .build())
          .retrieve()
          .toBodilessEntity();
      return LinkStatus.LINKED;
    } catch (RestClientResponseException e) {
      Integer kakaoCode = kakaoErrorCode(e);
      // 연결 해제와 같은 계약: "HTTP 400 + code -101"만 연결되지 않은 사용자로 본다
      if (e.getStatusCode().value() == 400 && kakaoCode != null && kakaoCode == NOT_REGISTERED_USER) {
        return LinkStatus.UNLINKED;
      }
      log.warn("카카오 연결 상태 조회 실패: status={}, kakaoCode={}", e.getStatusCode().value(), kakaoCode);
      throw new KakaoApiUnavailableException("카카오 연결 상태 조회 실패: status=" + e.getStatusCode().value());
    } catch (ResourceAccessException e) {
      log.warn("카카오 연결 상태 조회 실패: 연결 실패 또는 시간 초과 ({})", e.getClass().getSimpleName());
      throw new KakaoApiUnavailableException("카카오 연결 상태 조회 실패: " + e.getClass().getSimpleName());
    }
  }

  // 오류 본문의 code. 본문이 없거나 JSON이 아니면 null (성공으로 판단하지 않음)
  private static Integer kakaoErrorCode(RestClientResponseException e) {
    try {
      KakaoError error = e.getResponseBodyAs(KakaoError.class);
      return error == null ? null : error.code();
    } catch (RuntimeException parseFailure) {
      return null;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record KakaoError(Integer code) {
  }
}
