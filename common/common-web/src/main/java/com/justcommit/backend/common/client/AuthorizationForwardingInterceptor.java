package com.justcommit.backend.common.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;

final class AuthorizationForwardingInterceptor implements ClientHttpRequestInterceptor {

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
      throws IOException {
    if (request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION) == null) {
      String authorization = currentUserAuthorization();
      if (authorization != null) {
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, authorization);
      }
    }
    return execution.execute(request, body);
  }

  private static String currentUserAuthorization() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
      return attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
    }
    return null;
  }
}
