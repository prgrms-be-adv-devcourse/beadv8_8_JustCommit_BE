package com.justcommit.backend.product.search.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// 무중단 재색인 실행자 (#563). 작업 행이 서버 전체에서 하나만 돌게 막으므로 스레드 하나면 된다.
// 대기열 1칸은 실패 처리된 옛 실행이 아직 끝나지 않았을 때 새 작업이 그 뒤에 기다리게 하려고
@Configuration
public class SearchIndexJobConfig {

    @Bean
    public TaskExecutor searchIndexJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("search-index-job-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        // 서버를 내릴 때 실행 중인 재색인을 기다리지 않는다. 남은 작업은 하트비트가 끊겨 staleAfter 뒤 실패 처리됨
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
