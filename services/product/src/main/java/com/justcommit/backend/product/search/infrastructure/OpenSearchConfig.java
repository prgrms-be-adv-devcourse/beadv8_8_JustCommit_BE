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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// OpenSearch 연결. 값은 docs/conventions/resilience.md (product → OpenSearch: 연결 1초 · 읽기 2초 · 연결 풀 20 · 빌리기 대기 0.2초)
// 재시도(연결 실패만 1회)는 검색 호출 코드에서 한다 (#486 · #574): HttpClient 기본 재시도는 POST(_search)를 다시 보내지 않음
@Configuration
public class OpenSearchConfig {

    static final int DEFAULT_MAX_CONNECTIONS = 20;
    static final Duration DEFAULT_CONNECTION_REQUEST_TIMEOUT = Duration.ofMillis(200);

    private final String url;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int maxConnections;
    private final Duration connectionRequestTimeout;

    @Autowired
    public OpenSearchConfig(@Value("${custom.search.opensearch.url}") String url,
                            @Value("${custom.search.opensearch.connect-timeout}") Duration connectTimeout,
                            @Value("${custom.search.opensearch.read-timeout}") Duration readTimeout,
                            @Value("${custom.search.opensearch.max-connections}") int maxConnections,
                            @Value("${custom.search.opensearch.connection-request-timeout}") Duration connectionRequestTimeout) {
        this.url = url;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.maxConnections = maxConnections;
        this.connectionRequestTimeout = connectionRequestTimeout;
    }

    // 테스트용: 연결 풀 · 빌리기 대기는 운영 기본값
    public OpenSearchConfig(String url, Duration connectTimeout, Duration readTimeout) {
        this(url, connectTimeout, readTimeout, DEFAULT_MAX_CONNECTIONS, DEFAULT_CONNECTION_REQUEST_TIMEOUT);
    }

    // HTTP 연결을 들고 있는 객체. 앱이 꺼질 때 스프링이 close()를 불러 연결을 닫는다
    @Bean
    public OpenSearchTransport openSearchTransport() throws URISyntaxException {
        return ApacheHttpClient5TransportBuilder.builder(HttpHost.create(url))
                .setHttpClientConfigCallback(httpClient -> httpClient.setConnectionManager(
                        PoolingAsyncClientConnectionManagerBuilder.create()
                                // 연결 풀: 연결 관리자를 직접 만들면 HttpClient 기본값(서버당 5개)이 되므로 직접 정한다.
                                // OpenSearch가 응답 없이 멈췄을 때 기다리며 묶이는 요청 스레드의 상한이기도 하다 (#574)
                                .setMaxConnPerRoute(maxConnections)
                                .setMaxConnTotal(maxConnections)
                                // 연결 타임아웃: 접속(TCP 연결)이 이 시간 안에 안 되면 포기
                                .setDefaultConnectionConfig(ConnectionConfig.custom()
                                        .setConnectTimeout(Timeout.of(connectTimeout))
                                        .build())
                                .build()))
                .setRequestConfigCallback(request -> request
                        // 읽기 타임아웃: 요청을 보낸 뒤 응답이 이 시간 안에 안 오면 포기
                        .setResponseTimeout(Timeout.of(readTimeout))
                        // 빌리기 대기: 풀의 연결이 모두 쓰이는 중이면 이 시간만 기다리고 포기 (기본 3분이라 줄을 서던 문제, #574)
                        .setConnectionRequestTimeout(Timeout.of(connectionRequestTimeout)))
                .build();
    }

    // 인덱스 생성 · 색인 · 검색을 부르는 타입 있는 클라이언트
    @Bean
    public OpenSearchClient openSearchClient(OpenSearchTransport transport) {
        return new OpenSearchClient(transport);
    }
}
