package com.justcommit.backend.common.client;

import io.github.resilience4j.retry.Retry;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

final class InternalApiRetryInterceptor implements ClientHttpRequestInterceptor {

  private final Retry retry;

  InternalApiRetryInterceptor(Retry retry) {
    this.retry = retry;
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
      throws IOException {
    try {
      return retry.executeCheckedSupplier(() -> execution.execute(request, body));
    } catch (IOException | RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
  }
}
