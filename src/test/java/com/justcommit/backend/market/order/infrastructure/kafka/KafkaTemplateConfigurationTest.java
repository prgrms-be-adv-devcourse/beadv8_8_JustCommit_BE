package com.justcommit.backend.market.order.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaTemplateConfigurationTest {

    @Test
    void kafkaTemplateIsInjectedIntoOrderEventPublisher() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
                .withUserConfiguration(PurchaseConfirmedEventPublisher.class)
                .withPropertyValues("custom.kafka.topics.purchase-confirmed=market.purchase-confirmed.v1")
                .run(context -> {
                    assertThat(context).hasSingleBean(KafkaTemplate.class);
                    assertThat(context).hasSingleBean(PurchaseConfirmedEventPublisher.class);
                });
    }
}
