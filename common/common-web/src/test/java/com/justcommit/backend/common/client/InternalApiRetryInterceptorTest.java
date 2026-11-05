package com.justcommit.backend.common.client;

import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalApiRetryInterceptorTest {

  private final AtomicInteger attempts = new AtomicInteger();
  private final MockClientHttpRequest request = new MockClientHttpRequest();
  private final InternalApiRetryInterceptor interceptor = new InternalApiRetryInterceptor(
      Retry.of("payment", InternalApiClientAutoConfiguration.INTERNAL_RETRY));

  @Test
  @DisplayName("연결 실패는 한 번 더 시도한다")
  void retriesOnceOnConnectionFailure() {
    assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], (req, body) -> {
      attempts.incrementAndGet();
      throw new InternalApiUnavailableException("payment", new ConnectException());
    })).isInstanceOf(InternalApiUnavailableException.class);

    assertThat(attempts).hasValue(2);
  }

  @Test
  @DisplayName("재시도에서 성공하면 응답을 돌려준다")
  void returnsResponseWhenRetrySucceeds() throws IOException {
    ClientHttpResponse ok = new MockClientHttpResponse(new byte[0], HttpStatus.OK);

    ClientHttpResponse response = interceptor.intercept(request, new byte[0], (req, body) -> {
      if (attempts.incrementAndGet() == 1) {
        throw new InternalApiUnavailableException("payment", new ConnectException());
      }
      return ok;
    });

    assertThat(response).isSameAs(ok);
    assertThat(attempts).hasValue(2);
  }

  @Test
  @DisplayName("결과 불명은 재시도하지 않는다")
  void doesNotRetryResultUnknown() {
    assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], (req, body) -> {
      attempts.incrementAndGet();
      throw new InternalApiResultUnknownException("payment", new HttpTimeoutException("timeout"));
    })).isInstanceOf(InternalApiResultUnknownException.class);

    assertThat(attempts).hasValue(1);
  }
}
