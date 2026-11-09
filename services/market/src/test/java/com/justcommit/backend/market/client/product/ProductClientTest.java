package com.justcommit.backend.market.client.product;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.havingExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.justcommit.backend.market.client.InternalApiClientTestSupport.INTERNAL_KEY;
import static com.justcommit.backend.market.client.InternalApiClientTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.client.InternalApiClientTestSupport;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductClientTest {

    private static final String RESERVATIONS = "/internal/v1/products/reservations";

    @RegisterExtension
    static WireMockExtension product = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private ApplicationContextRunner runner() {
        return InternalApiClientTestSupport.runner(product.baseUrl(), ProductClient.class);
    }

    @Test
    @DisplayName("여러 건 조회는 ids로 한 번에 부르고 내부 키를 붙인다")
    void getsProductsByIds() {
        product.stubFor(get(urlPathEqualTo("/internal/v1/products"))
                .withQueryParam("ids", havingExactly("101", "102"))
                .willReturn(okJson(json("product/products.json"))));

        runner().run(context -> {
            List<ProductResult> products = context.getBean(ProductQuery.class).getProducts(List.of(101L, 102L));

            assertThat(products).extracting(ProductResult::productId).containsExactly(101L, 102L);
            assertThat(products.get(0).price()).isEqualByComparingTo("10000");
            assertThat(products.get(0).isPurchasable()).isTrue();
            assertThat(products.get(1).status()).isEqualTo(ProductStatus.REVIEW_PENDING);
            assertThat(products.get(1).isPurchasable()).isFalse();
        });
        product.verify(getRequestedFor(urlPathEqualTo("/internal/v1/products"))
                .withHeader("X-Internal-Key", equalTo(INTERNAL_KEY)));
    }

    @Test
    @DisplayName("단건 조회는 여러 건 API를 id 하나로 부르고, 결과가 없으면 PRODUCT_NOT_FOUND")
    void getProductThrowsNotFoundWhenMissing() {
        product.stubFor(get(urlPathEqualTo("/internal/v1/products"))
                .withQueryParam("ids", havingExactly("999"))
                .willReturn(okJson(json("product/empty.json"))));

        runner().run(context -> assertThatThrownBy(() -> context.getBean(ProductQuery.class).getProduct(999L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_NOT_FOUND)));
    }

    @Test
    @DisplayName("빈 목록은 product를 부르지 않는다")
    void skipsCallForEmptyIds() {
        runner().run(context -> assertThat(context.getBean(ProductQuery.class).getProducts(List.of())).isEmpty());

        product.verify(0, getRequestedFor(urlPathEqualTo("/internal/v1/products")));
    }

    @Test
    @DisplayName("예약은 Idempotency-Key에 orderId, 상품마다 expectedPrice를 보낸다")
    void reservesWithOrderIdKeyAndExpectedPrices() {
        product.stubFor(post(RESERVATIONS).willReturn(okJson(json("product/reserved.json"))));

        runner().run(context -> context.getBean(ProductUseCase.class).reserve(99L, List.of(
                new ReservationItem(101L, new BigDecimal("10000")),
                new ReservationItem(102L, new BigDecimal("20000")))));

        product.verify(postRequestedFor(urlPathEqualTo(RESERVATIONS))
                .withHeader("Idempotency-Key", equalTo("99"))
                .withHeader("X-Internal-Key", equalTo(INTERNAL_KEY))
                .withRequestBody(equalToJson("""
                        {"orderId": 99, "items": [
                          {"productId": 101, "expectedPrice": 10000},
                          {"productId": 102, "expectedPrice": 20000}
                        ]}""")));
    }

    @Test
    @DisplayName("예약 409 PRICE_CHANGED는 다시 부르지 않고 PRICE_CHANGED로 주문을 막는다")
    void priceChangedConflictBecomesPriceChanged() {
        product.stubFor(post(RESERVATIONS).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody(json("product/price-changed.json"))));

        runner().run(context -> assertThatThrownBy(() -> reserveOne(context.getBean(ProductUseCase.class)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.PRICE_CHANGED)));
        product.verify(1, postRequestedFor(urlPathEqualTo(RESERVATIONS)));
    }

    @Test
    @DisplayName("그 밖의 예약 409는 PRODUCT_UNAVAILABLE로 주문을 막는다")
    void otherConflictBecomesProductUnavailable() {
        product.stubFor(post(RESERVATIONS).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody(json("product/not-reservable.json"))));

        runner().run(context -> assertThatThrownBy(() -> reserveOne(context.getBean(ProductUseCase.class)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_UNAVAILABLE)));
        product.verify(1, postRequestedFor(urlPathEqualTo(RESERVATIONS)));
    }

    @Test
    @DisplayName("예약 응답 타임아웃은 결과 불명으로 알리고 다시 부르지 않는다")
    void reservationTimeoutIsResultUnknown() {
        product.stubFor(post(RESERVATIONS).willReturn(okJson(json("product/reserved.json")).withFixedDelay(1000)));

        runner().run(context -> assertThatThrownBy(() -> reserveOne(context.getBean(ProductUseCase.class)))
                .isInstanceOf(InternalApiResultUnknownException.class));
        product.verify(1, postRequestedFor(urlPathEqualTo(RESERVATIONS)));
    }

    private static void reserveOne(ProductUseCase productUseCase) {
        productUseCase.reserve(99L, List.of(new ReservationItem(101L, new BigDecimal("10000"))));
    }
}
