package com.justcommit.backend.common.messaging.consumer;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 원래토픽.DLT로 보내는 전용 프로듀서.
 * 서비스의 Kafka 설정(주소 등)은 그대로 쓰고, 값 종류(봉투 · 문자열 · 원래 바이트)에 맞는 직렬화기를 골라 원래 형식대로 보낸다.
 * 빈 타입이 KafkaTemplate이 아니라 Boot 기본 KafkaTemplate에 영향 없음. 앱이 꺼질 때 Spring이 close()로 정리한다.
 */
public class DeadLetterProducer {

  private final DefaultKafkaProducerFactory<String, Object> factory;
  private final KafkaTemplate<String, Object> template;

  DeadLetterProducer(ProducerFactory<?, ?> base, EventEnvelopeMapper mapper) {
    Map<String, Object> config = new HashMap<>(base.getConfigurationProperties());
    config.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);

    Map<Class<?>, Serializer<?>> serializers = new LinkedHashMap<>();
    serializers.put(EventEnvelope.class, new EventEnvelopeSerializer(mapper)); // 처리 실패
    serializers.put(String.class, new StringSerializer());                      // 문자열로 받는 서비스
    serializers.put(byte[].class, new ByteArraySerializer());                  // 형식 오류(원래 바이트)

    this.factory = new DefaultKafkaProducerFactory<>(config, new StringSerializer(),
        new DelegatingByTypeSerializer(serializers));
    this.template = new KafkaTemplate<>(factory);
  }

  KafkaTemplate<String, Object> template() {
    return template;
  }

  // 앱이 꺼질 때 Spring이 부름 (@Bean의 close() 자동 인식): Kafka 연결 정리
  public void close() {
    factory.destroy();
  }
}
