package com.justcommit.backend.settlement.infrastructure.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.settlement.application.SettlementService;
import com.justcommit.backend.settlement.client.payment.SettlementPayoutClient;
import com.justcommit.backend.settlement.domain.Settlement;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 실제 Kafka · PostgreSQL로 구매 확정 수신 → 정산 생성을 확인 (payment 호출만 목)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PurchaseConfirmedKafkaIntegrationTest {

    private static final String LEGACY_TOPIC = "market.purchase-confirmed.v1";
    private static final String DLT = KafkaTopics.MARKET_EVENTS + ".DLT";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    // 그룹 · 역직렬화 · offset 설정은 application.yml 그대로, 주소만 테스트 컨테이너로 바꿈
    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("jwt.public-key", PurchaseConfirmedKafkaIntegrationTest::newPublicKey);
        registry.add("internal-api.key", () -> "test-internal-key");
    }

    private static final EventEnvelopeMapper ENVELOPE_MAPPER = new EventEnvelopeMapper();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired SettlementRepository settlementRepository;
    @MockitoSpyBean SettlementService settlementService;
    @MockitoBean SettlementPayoutClient settlementPayoutClient;

    @Test
    @DisplayName("market.events.v1의 PurchaseConfirmed 봉투를 받으면 정산 1건이 생기고 배송비가 저장된다")
    void createsSettlementWithShippingFee() {
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "1001", purchaseConfirmedEnvelope(1001L, "3000"));

        Settlement settlement = awaitSettlement(1001L);
        assertThat(settlement.getSaleAmount()).isEqualByComparingTo("30000");
        assertThat(settlement.getShippingFee()).isEqualByComparingTo("3000");
        // 수수료 5%는 판매액에만, 배송비는 정산금에 넣지 않음
        assertThat(settlement.getFee()).isEqualByComparingTo("1500");
        assertThat(settlement.getPayoutAmount()).isEqualByComparingTo("28500");
    }

    @Test
    @DisplayName("같은 sellerOrderId가 옛 토픽과 새 토픽으로 한 번씩 와도 정산은 1건이다")
    void sameSellerOrderFromBothTopicsSettlesOnce() {
        kafkaTemplate.send(LEGACY_TOPIC, "2001", legacyJson(2001L));
        Settlement first = awaitSettlement(2001L);
        assertThat(first.getShippingFee()).isNull();

        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "2001", purchaseConfirmedEnvelope(2001L, "3000"));

        // 두 번째 수신까지 처리됐는지 확인한 뒤 건수를 봄
        verify(settlementService, timeout(20_000).times(2))
                .settle(eq(2001L), anyLong(), any(), any(), any());
        assertThat(settlementRepository.findAll())
                .filteredOn(s -> s.getSellerOrderId().equals(2001L))
                .singleElement()
                .satisfies(s -> assertThat(s.getId()).isEqualTo(first.getId()));
        verify(settlementPayoutClient, times(1)).distributeSettlement(eq(first.getId()), anyLong(), any(), any());
    }

    @Test
    @DisplayName("PurchaseConfirmed가 아닌 eventType은 정산을 만들지 않고 예외나 DLT 없이 넘어간다")
    void ignoresOtherEventTypesWithoutDlt() {
        // 같은 Key라 같은 파티션: 앞 메시지가 막히면(재시도 · DLT) 뒤 구매 확정도 늦게 처리됨
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "3001", envelope("ShipmentStatusChanged", 3001L,
                Map.of("sellerOrderId", 3001L, "status", "PICKED_UP")));
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "3001", purchaseConfirmedEnvelope(3001L, "3000"));

        awaitSettlement(3001L);
        verify(settlementService, times(1)).settle(eq(3001L), anyLong(), any(), any(), any());
        assertThat(pollDlt("3001", Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("형식이 깨진 메시지는 재시도 없이 market.events.v1.DLT로 간다")
    void malformedMessageGoesToDltWithoutRetry() {
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "4001", "not-json");

        List<ConsumerRecord<String, String>> dlt = pollDlt("4001", Duration.ofSeconds(30));
        assertThat(dlt).singleElement().satisfies(r -> assertThat(r.value()).isEqualTo("not-json"));
    }

    @Test
    @DisplayName("sellerOrderId가 없는 PurchaseConfirmed는 정산을 부르지 않고 바로 DLT로 간다")
    void payloadWithoutSellerOrderIdGoesToDltWithoutRetry() {
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "5001", envelope("PurchaseConfirmed", 5001L,
                Map.of("sellerId", 7L, "saleAmount", 30000)));

        assertThat(pollDlt("5001", Duration.ofSeconds(30))).hasSize(1);
        verify(settlementService, never()).settle(any(), any(), any(), any(), any());
    }

    private Settlement awaitSettlement(Long sellerOrderId) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            var found = settlementRepository.findBySellerOrderId(sellerOrderId);
            if (found.isPresent()) {
                return found.get();
            }
            sleep();
        }
        throw new AssertionError("정산이 생기지 않음: sellerOrderId=" + sellerOrderId);
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // DLT에서 이 key의 메시지를 기다린다 (없으면 빈 목록)
    private List<ConsumerRecord<String, String>> pollDlt(String key, Duration wait) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(DLT));
            long deadline = System.currentTimeMillis() + wait.toMillis();
            while (System.currentTimeMillis() < deadline) {
                List<ConsumerRecord<String, String>> found = StreamSupport
                        .stream(consumer.poll(Duration.ofMillis(500)).spliterator(), false)
                        .filter(r -> key.equals(r.key()))
                        .toList();
                if (!found.isEmpty()) {
                    return found;
                }
            }
            return List.of();
        }
    }

    private static String purchaseConfirmedEnvelope(Long sellerOrderId, String shippingFee) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sellerOrderId", sellerOrderId);
        payload.put("sellerId", 7L);
        payload.put("saleAmount", new BigDecimal("30000"));
        payload.put("shippingFee", new BigDecimal(shippingFee));
        payload.put("confirmedAt", "2026-11-12T10:00:00");
        payload.put("itemSummary", "몬스테라 외 1건");
        return envelope("PurchaseConfirmed", sellerOrderId, payload);
    }

    private static String envelope(String eventType, Long sellerOrderId, Map<String, ?> payload) {
        return ENVELOPE_MAPPER.toJson(new EventEnvelope(UUID.randomUUID().toString(), eventType, 1,
                OffsetDateTime.now(), String.valueOf(sellerOrderId), JSON.valueToTree(payload)));
    }

    // 옛 market이 market.purchase-confirmed.v1로 보내던 JSON (봉투 없음, shippingFee 없음)
    private static String legacyJson(Long sellerOrderId) {
        return """
                {"sellerOrderId":%d,"sellerId":7,"saleAmount":30000,"confirmedAt":"2026-11-12T10:00:00","itemSummary":"몬스테라"}
                """.formatted(sellerOrderId).strip();
    }

    private static String newPublicKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
