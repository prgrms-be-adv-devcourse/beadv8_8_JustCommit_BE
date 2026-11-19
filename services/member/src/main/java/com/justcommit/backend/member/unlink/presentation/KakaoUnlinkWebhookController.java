package com.justcommit.backend.member.unlink.presentation;

import com.justcommit.backend.member.unlink.application.KakaoUnlinkReceiver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카카오 연결 해제 웹훅 (#422). 카카오가 GET 쿼리 또는 POST 폼으로 보낸다.
 * 키가 틀리면 401(기록 없음), 맞으면 값과 관계없이 200. 회원 처리는 여기서 하지 않음
 */
@RestController
@RequiredArgsConstructor
public class KakaoUnlinkWebhookController {

  private final KakaoUnlinkReceiver receiver;

  @RequestMapping(value = "/api/v1/webhooks/kakao/unlink", method = {RequestMethod.GET, RequestMethod.POST})
  public ResponseEntity<Void> receive(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestParam(value = "app_id", required = false) String appId,
      @RequestParam(value = "user_id", required = false) String userId,
      @RequestParam(value = "referrer_type", required = false) String referrerType) {
    if (!receiver.isAuthorized(authorization)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    receiver.receive(appId, userId, referrerType);
    return ResponseEntity.ok().build();
  }
}
