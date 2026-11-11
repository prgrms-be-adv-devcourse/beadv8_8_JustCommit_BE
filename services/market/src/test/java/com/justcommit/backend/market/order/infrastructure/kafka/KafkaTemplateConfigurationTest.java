package com.justcommit.backend.market.order.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class KafkaTemplateConfigurationTest {

    @Test
    void outboxPublisherIsInjectedIntoOrderEventPublisher() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxPublisher.class);
                    assertThat(context).hasSingleBean(PurchaseConfirmedEventPublisher.class);
                });
    }

    @Configuration
    @Import(PurchaseConfirmedEventPublisher.class)
    static class TestConfiguration {
        @Bean
        OutboxPublisher outboxPublisher() {
            return org.mockito.Mockito.mock(OutboxPublisher.class);
        }
    }
}
