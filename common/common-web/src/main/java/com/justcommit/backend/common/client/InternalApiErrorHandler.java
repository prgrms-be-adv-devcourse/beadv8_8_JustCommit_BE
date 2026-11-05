package com.justcommit.backend.common.client;

import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

final class InternalApiErrorHandler implements RestClient.ResponseSpec.ErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(InternalApiErrorHandler.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String group;

  InternalApiErrorHandler(String group) {
    this.group = group;
  }

  @Override
  public void handle(HttpRequest request, ClientHttpResponse response) throws IOException {
    int status = response.getStatusCode().value();
    ApiResponse<?> body = readBody(response.getBody().readAllBytes(), status);

    String code = (body != null) ? body.code() : null;
    String message = (body != null && body.message() != null)
        ? body.message()
        : group + " 서비스가 " + status + " 응답을 보냈습니다.";

    throw new InternalApiErrorException(group, status, code, message);
  }

  private ApiResponse<?> readBody(byte[] bytes, int status) {
    if (bytes.length == 0) {
      return null;
    }
    try {
      return JSON.readValue(bytes, ApiResponse.class);
    } catch (JacksonException e) {
      log.debug("Internal API error body is not ApiResponse: group={}, status={}", group, status);
      return null;
    }
  }
}
