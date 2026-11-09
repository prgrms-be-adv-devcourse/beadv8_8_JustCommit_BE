package com.justcommit.backend.market.client;

import com.justcommit.backend.common.client.InternalApiClientAutoConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// 내부 API 클라이언트를 실제 그룹 설정(내부 키 · 재시도 · 에러 변환)으로 띄우고 주소만 WireMock으로 바꾼다
public final class InternalApiClientTestSupport {

    public static final String INTERNAL_KEY = "test-key";

    private InternalApiClientTestSupport() {
    }

    public static ApplicationContextRunner runner(String baseUrl, Class<?> client) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        HttpClientAutoConfiguration.class,
                        ImperativeHttpClientAutoConfiguration.class,
                        HttpServiceClientPropertiesAutoConfiguration.class,
                        RestClientAutoConfiguration.class,
                        HttpServiceClientAutoConfiguration.class,
                        InternalApiClientAutoConfiguration.class))
                .withUserConfiguration(InternalApiClientConfig.class, client)
                .withPropertyValues(
                        "internal-api.key=" + INTERNAL_KEY,
                        "internal-api.groups=product,payment,settlement",
                        "spring.http.serviceclient.product.base-url=" + baseUrl,
                        "spring.http.serviceclient.payment.base-url=" + baseUrl,
                        "spring.http.serviceclient.settlement.base-url=" + baseUrl,
                        "spring.http.serviceclient.product.read-timeout=500ms",
                        "spring.http.serviceclient.payment.read-timeout=500ms",
                        "spring.http.serviceclient.settlement.read-timeout=500ms");
    }

    // src/test/resources/wiremock/{상대}/ 아래 응답 JSON
    public static String json(String path) {
        try (InputStream in = InternalApiClientTestSupport.class.getResourceAsStream("/wiremock/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("WireMock 응답 파일이 없습니다: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
