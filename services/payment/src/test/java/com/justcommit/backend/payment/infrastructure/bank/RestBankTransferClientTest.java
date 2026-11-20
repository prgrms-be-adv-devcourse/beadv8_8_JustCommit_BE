package com.justcommit.backend.payment.infrastructure.bank;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.client.InternalApiClientAutoConfiguration;
import com.justcommit.backend.payment.config.BankClientConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 은행 지급을 실제 그룹 설정(bank)으로 보내고, 은행 자리의 WireMock이 받은 요청과 응답별 결과를 확인한다
// 내부 API 그룹(internal-member)도 켜 두고, 은행 요청에 내부 키가 붙지 않는지 본다
@ExtendWith(OutputCaptureExtension.class)
class RestBankTransferClientTest {

    private static final String TRANSFERS = "/api/v1/transfers";
    private static final String BANK_KEY = "test-bank-key";
    private static final String INTERNAL_KEY = "test-internal-key";
    private static final String ACCOUNT_NO = "11122233334444";
    private static final BankTransferRequest REQUEST =
            new BankTransferRequest("15", "004", ACCOUNT_NO, "홍길동", new BigDecimal("35000"));

    // 은행 서버(Node)는 h2c 업그레이드를 무시하고 HTTP/1.1로 답한다. WireMock(Jetty)은 업그레이드를 받아 POST 응답이 끊기므로 끈다
    @RegisterExtension
    static WireMockExtension bank = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().http2PlainDisabled(true))
            .build();

    @Test
    @DisplayName("200이면 transferId를 돌려주고, X-Api-Key와 Idempotency-Key, 본문 4칸을 보내며 내부 키는 보내지 않는다")
    void transfers() {
        bank.stubFor(post(TRANSFERS).willReturn(okJson("""
                {"status":200,"message":"지급이 완료되었습니다.",
                 "data":{"transferId":"tr-1","idempotencyKey":"15","status":"COMPLETED","amount":35000}}
                """)));

        run(client -> assertThat(client.transfer(REQUEST)).isEqualTo(new BankTransferResult("15", "tr-1")));

        bank.verify(1, postRequestedFor(urlPathEqualTo(TRANSFERS))
                .withHeader("X-Api-Key", equalTo(BANK_KEY))
                .withHeader("Idempotency-Key", equalTo("15"))
                .withHeader("X-Internal-Key", absent())
                .withRequestBody(equalToJson("""
                        {"bankCode":"004","accountNo":"11122233334444","accountHolder":"홍길동","amount":35000}
                        """)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "422, INVALID_ACCOUNT, INVALID_ACCOUNT",
            "422, ACCOUNT_HOLDER_MISMATCH, ACCOUNT_HOLDER_MISMATCH",
            "409, IDEMPOTENCY_KEY_CONFLICT, ",
            "401, UNAUTHORIZED, ",
            "400, BAD_REQUEST_DATA, ",
            "500, INTERNAL_ERROR, ",
            "503, SERVICE_UNAVAILABLE, "
    })
    @DisplayName("200이 아니면 예외를 던지고, 422면 failureCode를 메시지와 로그에 남긴다. 계좌번호 원문은 남기지 않는다")
    void failsOnErrorResponse(int status, String code, String failureCode, CapturedOutput output) {
        String data = failureCode == null ? "" : """
                ,"data":{"transferId":"tr-1","status":"FAILED","failureCode":"%s","accountLast4":"4444"}""".formatted(failureCode);
        bank.stubFor(post(TRANSFERS).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"status":%d,"code":"%s","message":"실패"%s}""".formatted(status, code, data))));

        run(client -> assertThatThrownBy(() -> client.transfer(REQUEST))
                .isInstanceOf(BankTransferException.class)
                .hasMessageContaining("idempotencyKey=15")
                .hasMessageContaining("status=" + status)
                .hasMessageContaining("code=" + code)
                .hasMessageContaining(failureCode == null ? "status=" : "failureCode=" + failureCode)
                .hasNoCause()
                .message().doesNotContain(ACCOUNT_NO));

        if (failureCode != null) {
            assertThat(output).contains("failureCode=" + failureCode);
        }
        assertThat(output).doesNotContain(ACCOUNT_NO);
    }

    @Test
    @DisplayName("응답이 5초(read-timeout)를 넘으면 결과 불명 예외를 던진다")
    void failsOnReadTimeout(CapturedOutput output) {
        bank.stubFor(post(TRANSFERS).willReturn(okJson("""
                {"status":200,"message":"ok","data":{"transferId":"tr-1"}}
                """).withFixedDelay(6_000)));

        run(client -> assertThatThrownBy(() -> client.transfer(REQUEST))
                .isInstanceOf(BankTransferException.class)
                .hasMessageContaining("결과 불명")
                .message().doesNotContain(ACCOUNT_NO));
        assertThat(output).doesNotContain(ACCOUNT_NO);
    }

    @Test
    @DisplayName("연결할 수 없으면 결과 불명 예외를 던진다")
    void failsOnConnectionRefused() {
        new ApplicationContextRunner()
                .withConfiguration(autoConfigurations())
                .withUserConfiguration(BankClientConfig.class)
                .withPropertyValues(properties("http://localhost:1"))
                .run(context -> assertThatThrownBy(() -> context.getBean(BankTransferClient.class).transfer(REQUEST))
                        .isInstanceOf(BankTransferException.class)
                        .hasMessageContaining("결과 불명"));
    }

    @Test
    @DisplayName("200인데 transferId가 없으면 예외를 던진다")
    void failsWithoutTransferId() {
        bank.stubFor(post(TRANSFERS).willReturn(okJson("""
                {"status":200,"message":"ok"}
                """)));

        run(client -> assertThatThrownBy(() -> client.transfer(REQUEST))
                .isInstanceOf(BankTransferException.class));
    }

    @Test
    @DisplayName("금액에 소수가 있으면 은행을 부르지 않고 예외를 던진다")
    void failsOnFractionalAmount() {
        BankTransferRequest fractional =
                new BankTransferRequest("15", "004", ACCOUNT_NO, "홍길동", new BigDecimal("35000.5"));

        run(client -> assertThatThrownBy(() -> client.transfer(fractional))
                .isInstanceOf(BankTransferException.class));
        bank.verify(0, postRequestedFor(urlPathEqualTo(TRANSFERS)));
    }

    private static void run(Consumer<BankTransferClient> assertion) {
        new ApplicationContextRunner()
                .withConfiguration(autoConfigurations())
                .withUserConfiguration(BankClientConfig.class)
                .withPropertyValues(properties(bank.baseUrl()))
                .run(context -> assertion.accept(context.getBean(BankTransferClient.class)));
    }

    static AutoConfigurations autoConfigurations() {
        return AutoConfigurations.of(
                HttpClientAutoConfiguration.class,
                ImperativeHttpClientAutoConfiguration.class,
                HttpServiceClientPropertiesAutoConfiguration.class,
                RestClientAutoConfiguration.class,
                HttpServiceClientAutoConfiguration.class,
                InternalApiClientAutoConfiguration.class);
    }

    // application.yml의 bank 그룹 설정과 같은 값
    private static String[] properties(String baseUrl) {
        return new String[]{
                "custom.bank.mode=http",
                "custom.bank.base-url=" + baseUrl,
                "custom.bank.api-key=" + BANK_KEY,
                "spring.http.serviceclient.bank.base-url=${custom.bank.base-url}",
                "spring.http.serviceclient.bank.connect-timeout=3s",
                "spring.http.serviceclient.bank.read-timeout=5s",
                "internal-api.key=" + INTERNAL_KEY,
                "internal-api.groups=internal-member"};
    }
}
