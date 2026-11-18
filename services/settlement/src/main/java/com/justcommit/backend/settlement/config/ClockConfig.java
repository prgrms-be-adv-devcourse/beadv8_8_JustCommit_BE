package com.justcommit.backend.settlement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// 정산 생성(settle)과 정산 지급 스케줄러가 같은 Clock에서 시각을 읽는다.
// 둘이 같은 시간대여야 next_attempt_at 비교와 임대 시각이 어긋나지 않는다
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
