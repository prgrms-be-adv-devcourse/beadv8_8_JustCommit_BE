package com.justcommit.backend.market.client;

import com.justcommit.backend.market.client.payment.PaymentApi;
import com.justcommit.backend.market.client.product.ProductApi;
import com.justcommit.backend.market.client.settlement.SettlementApi;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

// 그룹 이름은 internal-api.groups와 spring.http.serviceclient.<그룹>.base-url의 이름과 같다
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "product", types = ProductApi.class)
@ImportHttpServices(group = "payment", types = PaymentApi.class)
@ImportHttpServices(group = "settlement", types = SettlementApi.class)
public class InternalApiClientConfig {
}
