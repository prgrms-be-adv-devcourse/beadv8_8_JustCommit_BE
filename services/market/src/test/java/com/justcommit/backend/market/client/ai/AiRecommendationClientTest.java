package com.justcommit.backend.market.client.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.justcommit.backend.market.client.InternalApiClientTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.client.InternalApiHeaders;
import com.justcommit.backend.market.client.InternalApiClientTestSupport;
import com.justcommit.backend.market.client.ai.CartRecommendationQuery.Recommendation;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

class AiRecommendationClientTest {

    private static final String CART = "/internal/v1/recommendations/cart";

    @RegisterExtension
    static WireMockExtension ai = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    @DisplayName("정상: 장바구니 · 최근 구매 상품 ID와 size를 보내고 추천 상품 ID · 이유를 받는다")
    void returnsRecommendations() {
        ai.stubFor(post(CART).willReturn(okJson(json("ai/cart-recommendations.json"))));

        runner(ai.baseUrl()).run(context -> {
            List<Recommendation> result = query(context.getBean(CartRecommendationQuery.class));

            assertThat(result).containsExactly(
                    new Recommendation(201L, "몬스테라처럼 반그늘 실내에서 잘 자라요"),
                    new Recommendation(202L, "물주기 간격이 비슷해요"));
        });
        ai.verify(postRequestedFor(urlEqualTo(CART))
                .withHeader(InternalApiHeaders.INTERNAL_KEY, equalTo(InternalApiClientTestSupport.INTERNAL_KEY))
                .withRequestBody(equalToJson("""
                        {"cartProductIds":[101,102],"purchasedProductIds":[90],"size":8}
                        """)));
    }

    @Test
    @DisplayName("지연: 응답 타임아웃이면 기다리지 않고 빈 목록")
    void timeoutIsEmpty() {
        ai.stubFor(post(CART).willReturn(okJson(json("ai/cart-recommendations.json")).withFixedDelay(1000)));

        assertEmpty(runner(ai.baseUrl()));
    }

    @Test
    @DisplayName("지연: 운영 설정(응답 2초)에서 2초를 넘기면 빈 목록, 2초 남짓에 끝난다")
    void productionTimeoutIsTwoSeconds() {
        String readTimeout = applicationYml().getProperty("spring.http.serviceclient.ai.read-timeout");
        assertThat(readTimeout).isEqualTo("2s");
        ai.stubFor(post(CART).willReturn(okJson(json("ai/cart-recommendations.json")).withFixedDelay(3000)));

        runner(ai.baseUrl())
                .withPropertyValues("spring.http.serviceclient.ai.read-timeout=" + readTimeout)
                .run(context -> {
                    long start = System.nanoTime();
                    List<Recommendation> result = query(context.getBean(CartRecommendationQuery.class));
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                    assertThat(result).isEmpty();
                    assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(1900))
                            .isLessThan(Duration.ofMillis(2900));
                });
    }

    @Test
    @DisplayName("장애: 5xx면 빈 목록")
    void serverErrorIsEmpty() {
        ai.stubFor(post(CART).willReturn(serverError()));

        assertEmpty(runner(ai.baseUrl()));
    }

    @Test
    @DisplayName("장애: ai가 꺼져 있어 연결이 안 되면 빈 목록")
    void connectionFailureIsEmpty() throws IOException {
        assertEmpty(runner("http://localhost:" + closedPort()));
    }

    @Test
    @DisplayName("장애: 4xx도 추천 영역만 비우도록 빈 목록")
    void clientErrorIsEmpty() {
        ai.stubFor(post(CART).willReturn(aResponse().withStatus(400)));

        assertEmpty(runner(ai.baseUrl()));
    }

    @Test
    @DisplayName("본문 형식이 깨져 있어도 빈 목록")
    void malformedBodyIsEmpty() {
        ai.stubFor(post(CART).willReturn(okJson("{ \"data\": ")));

        assertEmpty(runner(ai.baseUrl()));
    }

    private static List<Recommendation> query(CartRecommendationQuery query) {
        return query.recommendForCart(List.of(101L, 102L), List.of(90L), 8);
    }

    private static void assertEmpty(ApplicationContextRunner runner) {
        runner.run(context -> assertThat(query(context.getBean(CartRecommendationQuery.class))).isEmpty());
    }

    private static ApplicationContextRunner runner(String baseUrl) {
        return InternalApiClientTestSupport.runner(baseUrl, AiRecommendationClient.class);
    }

    private static Properties applicationYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
