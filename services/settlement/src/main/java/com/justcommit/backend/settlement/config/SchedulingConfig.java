package com.justcommit.backend.settlement.config;

import com.justcommit.backend.settlement.application.payout.SettlementPayoutProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 정산 지급 스케줄러(#429) 설정. outbox 모듈도 @EnableScheduling을 켜지만(#437),
// 정산 지급이 outbox 모듈에 기대지 않게 여기서도 켜 둔다 (두 번 켜도 같음)
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SettlementPayoutProperties.class)
public class SchedulingConfig {
}
