package com.justcommit.backend.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// SSE 하트비트(NotificationSseRegistry.heartbeat, 30초 ping)를 돌리려면 스케줄링을 켜야 한다
// 모놀리스에서는 market 스케줄러의 @EnableScheduling이 대신 켜 주고 있었다
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
