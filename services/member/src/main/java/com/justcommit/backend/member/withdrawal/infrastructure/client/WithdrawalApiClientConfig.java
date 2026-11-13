package com.justcommit.backend.member.withdrawal.infrastructure.client;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

// 탈퇴 확인 내부 API 클라이언트 등록
// 그룹 이름은 internal-api.groups와 spring.http.serviceclient.<그룹>.base-url의 이름과 같다
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "market", types = MarketWithdrawalApi.class)
@ImportHttpServices(group = "payment", types = PaymentWithdrawalApi.class)
@ImportHttpServices(group = "settlement", types = SettlementWithdrawalApi.class)
public class WithdrawalApiClientConfig {
}
