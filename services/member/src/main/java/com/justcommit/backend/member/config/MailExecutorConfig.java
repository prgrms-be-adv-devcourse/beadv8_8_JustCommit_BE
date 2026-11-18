package com.justcommit.backend.member.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 메일 발송 전용 실행기 (#421). 인증 메일 · 가입 계정 안내 메일의 SMTP 호출을 요청 스레드 밖에서 처리한다.
 * - 스레드 수는 core = max로 고정: 스레드가 모두 바쁘면 대기열에 쌓고, 대기열도 차면 제출을 거절한다 (TaskRejectedException)
 * - defaultCandidate = false: 이름(@Qualifier)으로만 주입되어, Spring Boot 기본 실행기(applicationTaskExecutor)는 그대로 유지된다
 * - 설정값은 application.yml의 member.mail.executor (기본값 없음, 키가 빠지면 앱이 시작하지 않음)
 */
@Configuration
public class MailExecutorConfig {

  public static final String MAIL_TASK_EXECUTOR = "mailTaskExecutor";

  @Bean(name = MAIL_TASK_EXECUTOR, defaultCandidate = false)
  @Qualifier(MAIL_TASK_EXECUTOR)
  public ThreadPoolTaskExecutor mailTaskExecutor(@Value("${member.mail.executor.threads}") int threads,
                                                 @Value("${member.mail.executor.queue-capacity}") int queueCapacity) {
    if (threads < 1) {
      throw new IllegalArgumentException("member.mail.executor.threads는 1 이상이어야 합니다: " + threads);
    }
    if (queueCapacity < 0) {
      throw new IllegalArgumentException("member.mail.executor.queue-capacity는 0 이상이어야 합니다: " + queueCapacity);
    }
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(threads);
    executor.setMaxPoolSize(threads);
    executor.setQueueCapacity(queueCapacity);
    // 로그 구분 · 테스트에서 전용 실행기로 실행됐는지 확인할 때 사용
    executor.setThreadNamePrefix("mail-");
    return executor;
  }
}
