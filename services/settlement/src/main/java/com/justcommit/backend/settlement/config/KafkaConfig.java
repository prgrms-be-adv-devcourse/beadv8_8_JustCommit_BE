package com.justcommit.backend.settlement.config;

import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.support.converter.StringJacksonJsonMessageConverter;

// 기본 리스너 팩토리(봉투 · 재시도 1 · 2 · 4초 → DLT)는 common-messaging 공통 설정을 그대로 쓴다.
// 여기에는 옛 구매 확정 토픽(봉투가 아닌 JSON)용 팩토리만 둔다.
// TODO(#364): market이 market.events.v1로 옮겨 옛 토픽에 남은 메시지가 없으면 이 팩토리와 옛 토픽 리스너를 지운다
@Configuration
public class KafkaConfig {

    public static final String LEGACY_JSON_LISTENER_FACTORY = "legacyJsonListenerFactory";

    @Bean(LEGACY_JSON_LISTENER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> legacyJsonListenerFactory(
            ConsumerFactory<?, ?> consumerFactory,
            CommonErrorHandler errorHandler
    ) {
        // 주소 · 그룹 · offset 설정은 yml 그대로 쓰고, 값만 봉투 대신 문자열로 받는다
        DefaultKafkaConsumerFactory<String, String> stringConsumerFactory = new DefaultKafkaConsumerFactory<>(
                consumerFactory.getConfigurationProperties(), new StringDeserializer(), new StringDeserializer());

        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(stringConsumerFactory);
        // 보내는 쪽 이벤트 클래스명(타입 헤더)과 상관없이 JSON을 리스너 파라미터 타입으로 변환한다
        factory.setRecordMessageConverter(new StringJacksonJsonMessageConverter());
        // 실패 처리는 새 토픽과 같음 (재시도 1 · 2 · 4초 → market.purchase-confirmed.v1.DLT)
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
