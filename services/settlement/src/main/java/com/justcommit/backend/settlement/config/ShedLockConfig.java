package com.justcommit.backend.settlement.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

// @SchedulerLock이 붙은 스케줄러(정산 대사)는 shedlock 테이블(V8)에 잠금을 잡은 파드 한 곳에서만 돈다.
// 파드마다 시계가 조금씩 달라도 잠금 시각이 어긋나지 않게 DB 시각을 쓴다.
// 정산 지급 큐는 SKIP LOCKED와 임대로 막으므로 잠금을 쓰지 않는다
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
