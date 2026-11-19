package com.justcommit.backend.product.search.infrastructure;

import java.net.URISyntaxException;
import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// OpenSearch 연결. 타임아웃은 docs/conventions/resilience.md (product → OpenSearch: 연결 1초 · 읽기 2초)
// 재시도 1회는 검색 호출 코드에서 한다 (#486): HttpClient 기본 재시도는 POST(_search) · 타임아웃을 다시 보내지 않음
@Configuration
public class OpenSearchConfig {

    private final String url;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public OpenSearchConfig(@Value("${custom.search.opensearch.url}") String url,
                            @Value("${custom.search.opensearch.connect-timeout}") Duration connectTimeout,
                            @Value("${custom.search.opensearch.read-timeout}") Duration readTimeout) {
        this.url = url;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    // HTTP 연결을 들고 있는 객체. 앱이 꺼질 때 스프링이 close()를 불러 연결을 닫는다
    @Bean
    public OpenSearchTransport openSearchTransport() throws URISyntaxException {
        return ApacheHttpClient5TransportBuilder.builder(HttpHost.create(url))
                // 연결 타임아웃: 접속(TCP 연결)이 이 시간 안에 안 되면 포기
                .setHttpClientConfigCallback(httpClient -> httpClient.setConnectionManager(
                        PoolingAsyncClientConnectionManagerBuilder.create()
                                .setDefaultConnectionConfig(ConnectionConfig.custom()
                                        .setConnectTimeout(Timeout.of(connectTimeout))
                                        .build())
                                .build()))
                // 읽기 타임아웃: 요청을 보낸 뒤 응답이 이 시간 안에 안 오면 포기
                .setRequestConfigCallback(request -> request.setResponseTimeout(Timeout.of(readTimeout)))
                .build();
    }

    // 인덱스 생성 · 색인 · 검색을 부르는 타입 있는 클라이언트
    @Bean
    public OpenSearchClient openSearchClient(OpenSearchTransport transport) {
        return new OpenSearchClient(transport);
    }
}
