package com.justcommit.backend.settlement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJacksonJsonMessageConverter;

@Configuration
public class KafkaConfig {

    // 보내는 쪽 이벤트 클래스명(타입 헤더)과 상관없이 JSON을 리스너 파라미터 타입으로 변환한다
    @Bean
    RecordMessageConverter kafkaMessageConverter() {
        return new StringJacksonJsonMessageConverter();
    }
}
