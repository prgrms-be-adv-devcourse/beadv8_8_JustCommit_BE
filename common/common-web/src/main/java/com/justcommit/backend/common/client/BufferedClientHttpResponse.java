package com.justcommit.backend.common.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

// 본문을 미리 다 읽어 둔 응답. 상태 · 헤더 · 닫기는 원래 응답에 맡기고 본문만 메모리에서 꺼내 줌
// (본문을 읽다 난 타임아웃을 실패 변환 인터셉터 안에서 잡기 위해 씀)
final class BufferedClientHttpResponse implements ClientHttpResponse {

  private final ClientHttpResponse delegate;
  private final byte[] body;

  BufferedClientHttpResponse(ClientHttpResponse delegate, byte[] body) {
    this.delegate = delegate;
    this.body = body;
  }

  @Override
  public HttpStatusCode getStatusCode() throws IOException {
    return delegate.getStatusCode();
  }

  @Override
  public String getStatusText() throws IOException {
    return delegate.getStatusText();
  }

  @Override
  public HttpHeaders getHeaders() {
    return delegate.getHeaders();
  }

  @Override
  public InputStream getBody() {
    return new ByteArrayInputStream(body);
  }

  @Override
  public void close() {
    delegate.close();
  }
}
