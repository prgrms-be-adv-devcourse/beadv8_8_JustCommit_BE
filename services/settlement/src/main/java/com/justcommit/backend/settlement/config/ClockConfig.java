package com.justcommit.backend.settlement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// 정산 지급 스케줄러가 쓰는 시각. settle()이 LocalDateTime.now()(시스템 기본 시간대)로 next_attempt_at을 넣으므로
// 같은 시간대여야 지급 대상 비교 · 임대 시각이 어긋나지 않는다
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
