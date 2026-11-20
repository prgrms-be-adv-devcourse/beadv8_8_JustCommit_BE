package com.justcommit.backend.member.unlink.infrastructure.metrics;

import com.justcommit.backend.member.unlink.domain.KakaoUnlinkStatus;
import com.justcommit.backend.member.unlink.infrastructure.repository.KakaoUnlinkReceiptRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// 카카오 연결 해제 웹훅 운영 지표 (#422): 알림 규칙이 읽는 값
class KakaoUnlinkMetricsTest {

  private final KakaoUnlinkReceiptRepository repository = mock(KakaoUnlinkReceiptRepository.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final KakaoUnlinkMetrics metrics = new KakaoUnlinkMetrics(repository, registry);

  @Test
  @DisplayName("[#422] 저장 실패 카운터는 실패 전에도 0으로 등록되고, 실패할 때마다 1씩 오른다")
  void receiveFailuresCounter() {
    assertThat(registry.get(KakaoUnlinkMetrics.RECEIVE_FAILURES).counter().count()).isZero();

    metrics.receiveFailed();
    metrics.receiveFailed();

    assertThat(registry.get(KakaoUnlinkMetrics.RECEIVE_FAILURES).counter().count()).isEqualTo(2.0);
  }

  @Test
  @DisplayName("[#422] FAILED 수신 기록 수를 수집할 때마다 DB에서 센다")
  void failedGauge() {
    given(repository.countByStatus(KakaoUnlinkStatus.FAILED)).willReturn(3L);

    assertThat(registry.get(KakaoUnlinkMetrics.FAILED).gauge().value()).isEqualTo(3.0);
  }

  @Test
  @DisplayName("[#422] DB를 못 읽으면 0(정상)이 아니라 NaN(값 없음)")
  void failedGaugeNaNOnError() {
    given(repository.countByStatus(KakaoUnlinkStatus.FAILED)).willThrow(new DataAccessResourceFailureException("DB 실패"));

    assertThat(registry.get(KakaoUnlinkMetrics.FAILED).gauge().value()).isNaN();
  }

  @Test
  @DisplayName("[#422] 지표에 태그가 없다 (회원번호 · 시각은 복구 로그에만)")
  void noTags() {
    assertThat(registry.get(KakaoUnlinkMetrics.RECEIVE_FAILURES).counter().getId().getTags()).isEmpty();
    assertThat(registry.get(KakaoUnlinkMetrics.FAILED).gauge().getId().getTags()).isEmpty();
  }
}
