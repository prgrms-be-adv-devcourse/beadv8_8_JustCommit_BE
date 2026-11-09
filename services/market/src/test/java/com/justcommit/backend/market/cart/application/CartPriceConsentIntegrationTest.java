package com.justcommit.backend.market.cart.application;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.havingExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.justcommit.backend.market.client.InternalApiClientTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.client.InternalApiClientAutoConfiguration;
import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.cart.domain.Cart;
import com.justcommit.backend.market.cart.domain.CartItem;
import com.justcommit.backend.market.cart.infrastructure.CartItemRepository;
import com.justcommit.backend.market.cart.infrastructure.CartRepository;
import com.justcommit.backend.market.cart.presentation.CartItemPriceUpdateRequest;
import com.justcommit.backend.market.cart.presentation.CartResponse;
import com.justcommit.backend.market.client.InternalApiClientConfig;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.client.product.ProductQuery;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 가격 동의가 실제 DB 트랜잭션에서 전부 커밋되거나 전부 남지 않는지, product 조회는 실제 클라이언트로 WireMock을 불러 확인한다
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({
        HttpClientAutoConfiguration.class,
        ImperativeHttpClientAutoConfiguration.class,
        HttpServiceClientPropertiesAutoConfiguration.class,
        RestClientAutoConfiguration.class,
        HttpServiceClientAutoConfiguration.class,
        InternalApiClientAutoConfiguration.class})
@Import({JpaAuditingConfig.class, CartService.class, InternalApiClientConfig.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // CartService가 자기 트랜잭션을 열고 커밋한다
class CartPriceConsentIntegrationTest {

    private static final long MEMBER_ID = 10L;
    private static final String PRODUCTS = "/internal/v1/products";
    private static final String INTERNAL_KEY = "test-key";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @RegisterExtension
    static WireMockExtension product = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("internal-api.key", () -> INTERNAL_KEY);
        registry.add("spring.http.serviceclient.product.base-url", product::baseUrl);
        registry.add("spring.http.serviceclient.product.read-timeout", () -> "500ms");
    }

    // ProductClient는 패키지 밖에서 보이지 않아 그 패키지를 스캔해 띄운다
    @TestConfiguration
    @ComponentScan(basePackageClasses = ProductQuery.class)
    static class ProductClientScan {
    }

    @MockitoBean MemberQuery memberQuery;

    @Autowired CartService cartService;
    @Autowired CartRepository cartRepository;
    @Autowired CartItemRepository cartItemRepository;

    @AfterEach
    void cleanUp() {
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
    }

    @Test
    @DisplayName("고른 항목이 모두 동의한 가격과 같으면 product를 한 번 불러 두 항목의 저장 가격을 함께 커밋한다")
    void commitsAllConsentedPrices() {
        List<Long> ids = saveCart("10000", "20000");
        stubProducts();

        CartResponse response = cartService.updatePrices(MEMBER_ID, List.of(consent(ids.get(0), "12000"), consent(ids.get(1), "22000")));

        assertThat(savedPrices(ids)).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("12000"), new BigDecimal("22000"));
        assertThat(response.totalAmount()).isEqualByComparingTo("34000");
        assertThat(response.nowTotalAmount()).isEqualByComparingTo("34000");
        product.verify(1, getRequestedFor(urlPathEqualTo(PRODUCTS))
                .withQueryParam("ids", havingExactly("101", "102"))
                .withHeader("X-Internal-Key", equalTo(INTERNAL_KEY)));
    }

    @Test
    @DisplayName("한 항목이라도 가격이 다르면 PRICE_CHANGED, 맞는 항목도 DB에 바뀌지 않는다")
    void keepsAllPricesWhenOneDiffers() {
        List<Long> ids = saveCart("10000", "20000");
        stubProducts();

        assertThatThrownBy(() -> cartService.updatePrices(MEMBER_ID,
                List.of(consent(ids.get(0), "12000"), consent(ids.get(1), "21000"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.PRICE_CHANGED));

        assertThat(savedPrices(ids)).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("10000"), new BigDecimal("20000"));
    }

    @Test
    @DisplayName("product가 503이면 다시 부르지 않고 예외를 넘기며, 저장 가격은 그대로")
    void keepsPricesWhenProductUnavailable() {
        List<Long> ids = saveCart("10000", "20000");
        product.stubFor(get(urlPathEqualTo(PRODUCTS)).willReturn(serviceUnavailable()));

        assertThatThrownBy(() -> cartService.updatePrices(MEMBER_ID, List.of(consent(ids.get(0), "12000"))))
                .isInstanceOfSatisfying(InternalApiErrorException.class, e -> assertThat(e.status()).isEqualTo(503));

        assertThat(savedPrices(ids)).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("10000"), new BigDecimal("20000"));
        product.verify(1, getRequestedFor(urlPathEqualTo(PRODUCTS)));
    }

    @Test
    @DisplayName("product 응답이 늦으면 결과 불명 예외를 넘기고, 저장 가격은 그대로")
    void keepsPricesWhenProductTimesOut() {
        List<Long> ids = saveCart("10000", "20000");
        product.stubFor(get(urlPathEqualTo(PRODUCTS)).willReturn(okJson(json("product/cart-prices.json")).withFixedDelay(1000)));

        assertThatThrownBy(() -> cartService.updatePrices(MEMBER_ID, List.of(consent(ids.get(0), "12000"))))
                .isInstanceOf(InternalApiResultUnknownException.class);

        assertThat(savedPrices(ids)).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("10000"), new BigDecimal("20000"));
    }

    private List<Long> saveCart(String firstPrice, String secondPrice) {
        Cart cart = new Cart(MEMBER_ID);
        cart.addItem(new CartItem(cart, 101L, new BigDecimal(firstPrice)));
        cart.addItem(new CartItem(cart, 102L, new BigDecimal(secondPrice)));
        cartRepository.save(cart);
        return cart.getItems().stream().map(CartItem::getId).toList();
    }

    private List<BigDecimal> savedPrices(List<Long> cartItemIds) {
        return cartItemIds.stream().map(id -> cartItemRepository.findById(id).orElseThrow().getPrice()).toList();
    }

    private void stubProducts() {
        product.stubFor(get(urlPathEqualTo(PRODUCTS))
                .withQueryParam("ids", havingExactly("101", "102"))
                .willReturn(okJson(json("product/cart-prices.json"))));
    }

    private static CartItemPriceUpdateRequest.Item consent(long cartItemId, String expectedPrice) {
        return new CartItemPriceUpdateRequest.Item(cartItemId, new BigDecimal(expectedPrice));
    }
}
