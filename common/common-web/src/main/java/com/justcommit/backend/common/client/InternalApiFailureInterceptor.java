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
      return readFully(execution.execute(request, body));
    } catch (ConnectException | HttpConnectTimeoutException | UnknownHostException e) {
      throw new InternalApiUnavailableException(group, e);
    } catch (IOException e) {
      throw new InternalApiResultUnknownException(group, e);
    }
  }

  // 본문을 여기서 다 읽어 둠: 본문을 읽다 난 타임아웃도 intercept의 catch에서 결과 불명으로 바뀜
  private static ClientHttpResponse readFully(ClientHttpResponse response) throws IOException {
    try {
      return new BufferedClientHttpResponse(response, response.getBody().readAllBytes());
    } catch (IOException e) {
      // 읽다 실패하면 연결을 정리하고, 예외는 그대로 위로 보내 결과 불명으로 바뀌게 함
      response.close();
      throw e;
    }
  }
}
