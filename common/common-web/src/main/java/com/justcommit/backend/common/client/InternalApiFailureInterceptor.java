package com.justcommit.backend.common.client;

import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;

final class InternalApiFailureInterceptor implements ClientHttpRequestInterceptor {

  private final String group;

  InternalApiFailureInterceptor(String group) {
    this.group = group;
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
      throws IOException {
    try {
      return execution.execute(request, body);
    } catch (ConnectException | HttpConnectTimeoutException | UnknownHostException e) {
      throw new InternalApiUnavailableException(group, e);
    } catch (IOException e) {
      throw new InternalApiResultUnknownException(group, e);
    }
  }
}
