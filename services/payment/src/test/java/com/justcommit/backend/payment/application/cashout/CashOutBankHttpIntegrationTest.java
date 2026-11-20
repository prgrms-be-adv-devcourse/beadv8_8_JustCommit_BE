package com.justcommit.backend.payment.application.cashout;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.common.client.InternalApiClientAutoConfiguration;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.payment.client.member.MemberAccount;
import com.justcommit.backend.payment.client.member.MemberAccountClient;
import com.justcommit.backend.payment.config.BankClientConfig;
import com.justcommit.backend.payment.config.ClockConfig;
import com.justcommit.backend.payment.config.JpaAuditingConfig;
import com.justcommit.backend.payment.domain.CashOut;
import com.justcommit.backend.payment.domain.CashOutStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.infrastructure.bank.BankTransferClient;
import com.justcommit.backend.payment.infrastructure.bank.RestBankTransferClient;
import com.justcommit.backend.payment.infrastructure.crypto.AccountNoCipher;
import com.justcommit.backend.payment.infrastructure.crypto.PaymentKeyCipher;
import com.justcommit.backend.payment.infrastructure.repository.CashOutRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

// custom.bank.mode=http로 인출 신청을 끝까지 돌린다. 은행 자리는 WireMock, member 계좌 조회는 가짜로 바꾼다
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "custom.bank.mode=http",
        "custom.bank.api-key=test-bank-key",
        "spring.http.serviceclient.bank.base-url=${custom.bank.base-url}",
        "spring.http.serviceclient.bank.connect-timeout=3s",
        "spring.http.serviceclient.bank.read-timeout=5s"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({
        HttpClientAutoConfiguration.class,
        ImperativeHttpClientAutoConfiguration.class,
        HttpServiceClientPropertiesAutoConfiguration.class,
        RestClientAutoConfiguration.class,
        HttpServiceClientAutoConfiguration.class,
        InternalApiClientAutoConfiguration.class})
@Import({JpaAuditingConfig.class, ClockConfig.class, AccountNoCipher.class, PaymentKeyCipher.class,
        CashOutService.class, CashOutFacade.class, BankClientConfig.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
class CashOutBankHttpIntegrationTest {

    private static final Long MEMBER_ID = 41L;
    private static final String ACCOUNT_NO = "11122233334444";
    private static final String TRANSFERS = "/api/v1/transfers";

    // 은행 서버(Node)처럼 HTTP/1.1로만 답하게 한다 (RestBankTransferClientTest 참고)
    @RegisterExtension
    static WireMockExtension bank = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().http2PlainDisabled(true))
            .build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("custom.bank.base-url", bank::baseUrl);
    }

    @Autowired CashOutFacade cashOutFacade;
    @Autowired CashOutRepository cashOutRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired BankTransferClient bankTransferClient;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean MemberAccountClient memberAccountClient;

    @BeforeEach
    void setUp() {
        given(memberAccountClient.getAccount(MEMBER_ID)).willReturn(new MemberAccount("004", ACCOUNT_NO, "홍길동"));
        walletRepository.save(Wallet.forMember(MEMBER_ID));
        jdbcTemplate.update("update wallet set balance = 10000 where member_id = ?", MEMBER_ID);
    }

    @AfterEach
    void cleanUp() {
        cashOutRepository.deleteAllInBatch();
        walletTransactionRepository.deleteAllInBatch();
        jdbcTemplate.update("delete from wallet where wallet_type = 'MEMBER'");
    }

    @Test
    @DisplayName("http 모드에서 은행이 200이면 인출 건은 COMPLETED, Idempotency-Key는 인출 건 ID")
    void completesWhenBankSucceeds() {
        assertThat(bankTransferClient).isInstanceOf(RestBankTransferClient.class);
        bank.stubFor(post(TRANSFERS).willReturn(okJson("""
                {"status":200,"message":"지급이 완료되었습니다.","data":{"transferId":"tr-1","status":"COMPLETED"}}
                """)));

        CashOutResult result = cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(CashOutStatus.COMPLETED);
        assertThat(balance()).isEqualByComparingTo("7000");
        bank.verify(1, postRequestedFor(urlPathEqualTo(TRANSFERS))
                .withHeader("Idempotency-Key", equalTo(String.valueOf(result.cashOutId()))));
    }

    @Test
    @DisplayName("http 모드에서 은행이 500이면 인출 건은 PENDING으로 남고 잔액은 차감된 채로 둔다. 로그에 계좌번호 원문이 없다")
    void staysPendingWhenBankFails(CapturedOutput output) {
        bank.stubFor(post(TRANSFERS).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"status":500,"code":"INTERNAL_ERROR","message":"은행 내부 오류"}""")));

        CashOutResult result = cashOutFacade.requestCashOut(MEMBER_ID, "key-1", new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(CashOutStatus.PENDING);
        CashOut cashOut = cashOutRepository.findById(result.cashOutId()).orElseThrow();
        assertThat(cashOut.getStatus()).isEqualTo(CashOutStatus.PENDING);
        assertThat(cashOut.getCompletedAt()).isNull();
        assertThat(balance()).isEqualByComparingTo("7000");
        assertThat(output).contains("status=500").doesNotContain(ACCOUNT_NO);
    }

    private BigDecimal balance() {
        return walletRepository.findByMemberId(MEMBER_ID).orElseThrow().getBalance();
    }
}
