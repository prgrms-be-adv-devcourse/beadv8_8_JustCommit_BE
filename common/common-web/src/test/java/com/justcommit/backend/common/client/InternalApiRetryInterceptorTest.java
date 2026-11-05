package com.justcommit.backend.common.client;

import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import io.github.resilience4j.retry.Retry;
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
  void 연결_실패는_한_번_더_시도한다() {
    assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], (req, body) -> {
      attempts.incrementAndGet();
      throw new InternalApiUnavailableException("payment", new ConnectException());
    })).isInstanceOf(InternalApiUnavailableException.class);

    assertThat(attempts).hasValue(2);
  }

  @Test
  void 재시도에서_성공하면_응답을_돌려준다() throws IOException {
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
  void 결과_불명은_재시도하지_않는다() {
    assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], (req, body) -> {
      attempts.incrementAndGet();
      throw new InternalApiResultUnknownException("payment", new HttpTimeoutException("timeout"));
    })).isInstanceOf(InternalApiResultUnknownException.class);

    assertThat(attempts).hasValue(1);
  }
}
