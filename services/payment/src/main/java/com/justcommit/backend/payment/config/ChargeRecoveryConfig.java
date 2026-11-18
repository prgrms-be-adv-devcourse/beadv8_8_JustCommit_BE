package com.justcommit.backend.payment.config;

import com.justcommit.backend.payment.application.recovery.ChargeRecoveryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChargeRecoveryProperties.class)
public class ChargeRecoveryConfig {
}
