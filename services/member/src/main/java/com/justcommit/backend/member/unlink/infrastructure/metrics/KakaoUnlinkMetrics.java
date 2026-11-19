package com.justcommit.backend.member.unlink.infrastructure.metrics;

import com.justcommit.backend.member.unlink.domain.KakaoUnlinkStatus;
import com.justcommit.backend.member.unlink.infrastructure.repository.KakaoUnlinkReceiptRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 카카오 연결 해제 웹훅 운영 지표 (#422, docs/infra/observability.md 「알림 규칙」). 태그 없음 (회원번호 · 시각은 복구 로그에만)
//   replant_kakao_unlink_receive_failures_total  수신 기록을 저장하지 못한 웹훅 수. 200으로 응답해 5xx 알림에 안 잡히고 카카오가 다시 보내지 않음
//   replant_kakao_unlink_failed                  재시도를 다 써서 멈춘(FAILED) 수신 기록 수. Prometheus가 수집할 때마다 DB에서 셈
@Slf4j
@Component
public class KakaoUnlinkMetrics {

  static final String RECEIVE_FAILURES = "replant.kakao.unlink.receive.failures";
  static final String FAILED = "replant.kakao.unlink.failed";

  private final KakaoUnlinkReceiptRepository receiptRepository;
  private final Counter receiveFailures;

  public KakaoUnlinkMetrics(KakaoUnlinkReceiptRepository receiptRepository, MeterRegistry registry) {
    this.receiptRepository = receiptRepository;
    // 앱이 뜰 때 0으로 등록한다. 첫 실패 때 처음 만들면 increase()가 그 첫 증가를 놓친다
    this.receiveFailures = Counter.builder(RECEIVE_FAILURES)
        .description("수신 기록을 저장하지 못한 카카오 연결 해제 웹훅 수")
        .register(registry);
    Gauge.builder(FAILED, this, KakaoUnlinkMetrics::failedCount)
        .description("재시도를 다 써서 멈춘 카카오 연결 해제 웹훅 수신 기록 수")
        .register(registry);
  }

  public void receiveFailed() {
    receiveFailures.increment();
  }

  // DB를 못 읽으면 0(정상)이 아니라 NaN(값 없음). 알림 규칙이 이를 어떻게 보는지는 배포 후 확인 (흐름 문서 「운영 알림과 대응」)
  double failedCount() {
    try {
      return receiptRepository.countByStatus(KakaoUnlinkStatus.FAILED);
    } catch (RuntimeException e) {
      log.warn("멈춘 카카오 연결 해제 웹훅 수를 세지 못함: error={}", e.getClass().getSimpleName());
      return Double.NaN;
    }
  }
}
