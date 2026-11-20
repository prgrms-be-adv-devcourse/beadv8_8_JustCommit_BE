package com.justcommit.backend.payment.config;

import com.justcommit.backend.payment.infrastructure.bank.BankApi;
import com.justcommit.backend.payment.infrastructure.bank.BankProperties;
import com.justcommit.backend.payment.infrastructure.bank.BankTransferClient;
import com.justcommit.backend.payment.infrastructure.bank.RestBankTransferClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

// 모의 은행 서버 지급 (custom.bank.mode=http). fake 모드면 그룹 bank와 은행 설정을 만들지 않는다
// bank 그룹은 internal-api.groups에 넣지 않는다: 내부 API 키가 외부 은행으로 나가지 않게
// 주소, 타임아웃은 spring.http.serviceclient.bank (application.yml)
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "custom.bank", name = "mode", havingValue = "http")
@EnableConfigurationProperties(BankProperties.class)
@ImportHttpServices(group = "bank", types = BankApi.class)
public class BankClientConfig {

    @Bean
    BankTransferClient restBankTransferClient(BankApi bankApi, BankProperties bankProperties) {
        return new RestBankTransferClient(bankApi, bankProperties);
    }
}
