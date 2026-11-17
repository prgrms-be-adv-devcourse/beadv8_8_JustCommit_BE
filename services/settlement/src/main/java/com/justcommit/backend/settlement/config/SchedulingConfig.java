package com.justcommit.backend.settlement.config;

import com.justcommit.backend.settlement.application.payout.SettlementPayoutProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// settlement는 outbox 모듈(@EnableScheduling)을 쓰지 않아 여기서 스케줄링을 켠다 (정산 지급 스케줄러 #429)
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SettlementPayoutProperties.class)
public class SchedulingConfig {
}
