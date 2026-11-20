package com.justcommit.backend.payment.config;

import com.justcommit.backend.common.client.InternalApiClientAutoConfiguration;
import com.justcommit.backend.payment.infrastructure.bank.BankApi;
import com.justcommit.backend.payment.infrastructure.bank.BankProperties;
import com.justcommit.backend.payment.infrastructure.bank.BankTransferClient;
import com.justcommit.backend.payment.infrastructure.bank.FakeBankTransferClient;
import com.justcommit.backend.payment.infrastructure.bank.RestBankTransferClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// custom.bank.mode에 따라 은행 구현이 하나만 만들어지고, http 모드에서 설정이 비면 시작하지 않는지 확인한다
class BankClientConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    HttpClientAutoConfiguration.class,
                    ImperativeHttpClientAutoConfiguration.class,
                    HttpServiceClientPropertiesAutoConfiguration.class,
                    RestClientAutoConfiguration.class,
                    HttpServiceClientAutoConfiguration.class,
                    InternalApiClientAutoConfiguration.class))
            .withUserConfiguration(FakeBankTransferClient.class, BankClientConfig.class)
            .withPropertyValues("spring.http.serviceclient.bank.base-url=${custom.bank.base-url:}");

    @Test
    @DisplayName("모드가 없으면 FakeBankTransferClient만 있고 은행 그룹과 설정은 만들지 않는다 (설정이 비어도 시작한다)")
    void fakeByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBean(BankTransferClient.class).isInstanceOf(FakeBankTransferClient.class);
            assertThat(context).doesNotHaveBean(BankApi.class);
            assertThat(context).doesNotHaveBean(BankProperties.class);
        });
    }

    @Test
    @DisplayName("http 모드에 주소와 키가 있으면 RestBankTransferClient만 있다")
    void httpMode() {
        runner.withPropertyValues("custom.bank.mode=http", "custom.bank.base-url=http://localhost:3001",
                        "custom.bank.api-key=test-bank-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(BankTransferClient.class).isInstanceOf(RestBankTransferClient.class);
                });
    }

    @Test
    @DisplayName("http 모드에 base-url이 비어 있으면 BANK_BASE_URL을 알려 주며 시작하지 않는다")
    void failsWithoutBaseUrl() {
        runner.withPropertyValues("custom.bank.mode=http", "custom.bank.base-url=", "custom.bank.api-key=test-bank-key")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("BANK_BASE_URL"));
    }

    @Test
    @DisplayName("http 모드에 api-key가 비어 있으면 BANK_API_KEY를 알려 주며 시작하지 않는다")
    void failsWithoutApiKey() {
        runner.withPropertyValues("custom.bank.mode=http", "custom.bank.base-url=http://localhost:3001", "custom.bank.api-key= ")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("BANK_API_KEY"));
    }
}
