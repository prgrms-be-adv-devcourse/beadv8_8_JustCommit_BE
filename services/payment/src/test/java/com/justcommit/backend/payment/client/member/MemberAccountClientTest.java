package com.justcommit.backend.payment.client.member;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.client.InternalApiClientAutoConfiguration;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 계좌 조회를 실제 그룹 설정(internal-member)으로 보내고, member 자리의 WireMock 응답에 따라 결과를 확인한다
class MemberAccountClientTest {

    private static final String ACCOUNT = "/internal/v1/members/7/account";
    private static final String INTERNAL_KEY = "test-key";

    @RegisterExtension
    static WireMockExtension member = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    @DisplayName("정상 응답이면 은행 코드, 계좌번호, 예금주를 받고 내부 키를 보낸다")
    void returnsAccount() {
        member.stubFor(get(ACCOUNT).willReturn(okJson("""
                {"status":200,"code":"SUCCESS","message":"계좌를 조회했습니다.",
                 "data":{"bankCode":"004","accountNo":"12345678901234","accountHolder":"홍길동"}}
                """)));

        run(client -> {
            MemberAccount account = client.getAccount(7L);
            assertThat(account.bankCode()).isEqualTo("004");
            assertThat(account.accountNo()).isEqualTo("12345678901234");
            assertThat(account.accountHolder()).isEqualTo("홍길동");
            assertThat(account.accountLast4()).isEqualTo("1234");
            assertThat(account.toString()).doesNotContain("12345678901234");
        });

        member.verify(1, getRequestedFor(urlPathEqualTo(ACCOUNT))
                .withHeader("X-Internal-Key", equalTo(INTERNAL_KEY)));
    }

    @Test
    @DisplayName("404 MEMBER_NOT_FOUND면 계좌 없음으로 거절한다")
    void notFound() {
        member.stubFor(get(ACCOUNT).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"status":404,"code":"MEMBER_NOT_FOUND","message":"회원을 찾을 수 없습니다.","data":null}
                        """)));

        run(client -> assertError(client, PaymentErrorCode.CASH_OUT_ACCOUNT_NOT_FOUND));
    }

    @Test
    @DisplayName("5xx면 계좌 조회 실패로 거절하고 다시 부르지 않는다")
    void serverError() {
        member.stubFor(get(ACCOUNT).willReturn(aResponse().withStatus(500)));

        run(client -> assertError(client, PaymentErrorCode.ACCOUNT_LOOKUP_FAILED));
        member.verify(1, getRequestedFor(urlPathEqualTo(ACCOUNT)));
    }

    @Test
    @DisplayName("응답 타임아웃이면 계좌 조회 실패로 거절한다")
    void readTimeout() {
        member.stubFor(get(ACCOUNT).willReturn(okJson("{}").withFixedDelay(1_500)));

        run(client -> assertError(client, PaymentErrorCode.ACCOUNT_LOOKUP_FAILED),
                "spring.http.serviceclient.internal-member.read-timeout=500ms");
    }

    @Test
    @DisplayName("계좌 칸이 비어 있는 응답이면 계좌 조회 실패로 거절한다")
    void blankAccount() {
        member.stubFor(get(ACCOUNT).willReturn(okJson("""
                {"status":200,"code":"SUCCESS","message":"ok","data":{"bankCode":"004","accountNo":null,"accountHolder":"홍길동"}}
                """)));

        run(client -> assertError(client, PaymentErrorCode.ACCOUNT_LOOKUP_FAILED));
    }

    private static void assertError(MemberAccountClient client, PaymentErrorCode errorCode) {
        assertThatThrownBy(() -> client.getAccount(7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(errorCode);
    }

    private static void run(Consumer<MemberAccountClient> assertion, String... extraProperties) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        HttpClientAutoConfiguration.class,
                        ImperativeHttpClientAutoConfiguration.class,
                        HttpServiceClientPropertiesAutoConfiguration.class,
                        RestClientAutoConfiguration.class,
                        HttpServiceClientAutoConfiguration.class,
                        InternalApiClientAutoConfiguration.class))
                .withUserConfiguration(MemberClientConfig.class, MemberAccountClient.class)
                .withPropertyValues(
                        "internal-api.key=" + INTERNAL_KEY,
                        "internal-api.groups=internal-member",
                        "spring.http.serviceclient.internal-member.base-url=" + member.baseUrl())
                .withPropertyValues(extraProperties)
                .run(context -> assertion.accept(context.getBean(MemberAccountClient.class)));
    }

    // PaymentApplication의 @ImportHttpServices와 같은 그룹
    @Configuration(proxyBeanMethods = false)
    @ImportHttpServices(group = "internal-member", types = MemberInternalApi.class)
    static class MemberClientConfig {
    }
}
