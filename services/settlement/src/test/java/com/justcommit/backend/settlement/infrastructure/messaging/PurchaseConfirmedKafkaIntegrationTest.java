package com.justcommit.backend.settlement.infrastructure.messaging;

import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.settlement.application.SettlementService;
import com.justcommit.backend.settlement.client.payment.SettlementPayoutClient;
import com.justcommit.backend.settlement.domain.Settlement;
import com.justcommit.backend.settlement.domain.SettlementStatus;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.justcommit.backend.common.testcontainers.SharedKafka;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 실제 Kafka · PostgreSQL로 구매 확정 수신 → 정산 생성을 확인 (payment 호출만 목)
// 정산 지급 스케줄러는 끄고 수신만 본다. 수신은 PENDING 저장까지만 하고 payment를 부르지 않는다 (#428)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
// 리스너가 공유 Kafka의 다른 클래스 메시지를 읽지 않게 끝나면 컨텍스트를 닫는다
@DirtiesContext
class PurchaseConfirmedKafkaIntegrationTest {

    private static final String DLT = KafkaTopics.MARKET_EVENTS + ".DLT";

    // 그룹 · 역직렬화 · offset 설정은 application.yml 그대로, 주소만 테스트 컨테이너로 바꿈
    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        SharedKafka.register(registry);
        registry.add("jwt.public-key", PurchaseConfirmedKafkaIntegrationTest::newPublicKey);
        registry.add("internal-api.key", () -> "test-internal-key");
        registry.add("custom.settlement.payout.enabled", () -> "false");
        // Outbox 재전송 · 정리 스케줄러는 끈다 (common-messaging-outbox)
        registry.add("outbox.scheduler.enabled", () -> "false");
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
        // sellerBurdenAmount 칸이 없는 이벤트는 부담금 0 (V4 seller_burden_amount NOT NULL)
        assertThat(settlement.getSellerBurdenAmount()).isEqualByComparingTo("0");
        // 수신은 저장만: PENDING, 바로 지급 대상(next_attempt_at), 지급 전이라 완료 시각 없음 (V3 체크 제약도 통과)
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(settlement.getAttemptCount()).isZero();
        assertThat(settlement.getNextAttemptAt()).isNotNull();
        assertThat(settlement.getCompletedAt()).isNull();
        verify(settlementPayoutClient, never()).distributeSettlement(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("payment가 내려가 있어도(지급 호출이 예외) 구매 확정 수신은 성공하고 DLT로 가지 않는다")
    void receivesEvenWhenPaymentIsDown() {
        doThrow(new InternalApiUnavailableException("internal-payment", new RuntimeException("connection refused")))
                .when(settlementPayoutClient).distributeSettlement(any(), any(), any(), any(), any(), any(), any());

        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "6001", purchaseConfirmedEnvelope(6001L, "3000"));

        Settlement settlement = awaitSettlement(6001L);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(settlement.getCompletedAt()).isNull();
        verify(settlementPayoutClient, never()).distributeSettlement(any(), any(), any(), any(), any(), any(), any());
        assertThat(pollDlt("6001", Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("[S-H02] 같은 구매 확정 이벤트를 같은 토픽에서 두 번 받아도 정산은 1건, 수신만으로는 지급 요청 없음")
    void sameEventTwiceSettlesOnce() {
        String event = purchaseConfirmedEnvelope(4001L, "3000");
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "4001", event);
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "4001", event);

        Settlement settlement = awaitSettlement(4001L);
        // 두 번째 메시지까지 소비된 뒤 건수를 봄 (같은 Key라 같은 파티션에서 차례로 처리)
        verify(settlementService, timeout(20_000).times(2)).settle(eq(4001L), anyLong(), any(), any(), any(), any());
        assertThat(settlementRepository.findAll())
                .filteredOn(s -> s.getSellerOrderId().equals(4001L))
                .hasSize(1);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        // 지급은 정산 지급 스케줄러 몫 (같은 정산 한 번 지급은 SettlementPayoutServiceIntegrationTest)
        verify(settlementPayoutClient, never()).distributeSettlement(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PurchaseConfirmed가 아닌 eventType은 정산을 만들지 않고 예외나 DLT 없이 넘어간다")
    void ignoresOtherEventTypesWithoutDlt() {
        // 같은 Key라 같은 파티션: 앞 메시지가 막히면(재시도 · DLT) 뒤 구매 확정도 늦게 처리됨
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "3001", envelope("ShipmentStatusChanged", 3001L,
                Map.of("sellerOrderId", 3001L, "status", "PICKED_UP")));
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "3001", purchaseConfirmedEnvelope(3001L, "3000"));

        awaitSettlement(3001L);
        verify(settlementService, times(1)).settle(eq(3001L), anyLong(), any(), any(), any(), any());
        assertThat(pollDlt("3001", Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("[S-H04] 형식이 깨진 메시지는 재시도 없이 market.events.v1.DLT로 간다")
    void malformedMessageGoesToDltWithoutRetry() {
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "4001", "not-json");

        List<ConsumerRecord<String, String>> dlt = pollDlt("4001", Duration.ofSeconds(30));
        assertThat(dlt).singleElement().satisfies(r -> assertThat(r.value()).isEqualTo("not-json"));
    }

    @Test
    @DisplayName("[S-H04] sellerOrderId가 없는 PurchaseConfirmed는 정산을 부르지 않고 바로 DLT로 간다")
    void payloadWithoutSellerOrderIdGoesToDltWithoutRetry() {
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "5001", envelope("PurchaseConfirmed", 5001L,
                Map.of("sellerId", 7L, "saleAmount", 30000)));

        assertThat(pollDlt("5001", Duration.ofSeconds(30))).hasSize(1);
        verify(settlementService, never()).settle(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("market.events.v1의 SellerOrderReturned를 받으면 판매액, 수수료, 배송비 0, 정산금 -부담금인 PENDING 정산 1건이 생긴다")
    void createsReturnedSettlement() {
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "7001", sellerOrderReturnedEnvelope(7001L, "9000"));

        Settlement settlement = awaitSettlement(7001L);
        assertThat(settlement.getSaleAmount()).isEqualByComparingTo("0");
        assertThat(settlement.getFee()).isEqualByComparingTo("0");
        assertThat(settlement.getShippingFee()).isEqualByComparingTo("0");
        assertThat(settlement.getSellerBurdenAmount()).isEqualByComparingTo("9000");
        assertThat(settlement.getPayoutAmount()).isEqualByComparingTo("-9000");
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(settlement.getItemSummary()).isEqualTo("몬스테라 외 1건");
        assertThat(settlement.getCompletedAt()).isNull();
        verify(settlementPayoutClient, never()).distributeSettlement(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("같은 전부 반품 이벤트를 두 번 받아도 정산은 1건이다")
    void sameReturnedEventTwiceSettlesOnce() {
        String event = sellerOrderReturnedEnvelope(7002L, "9000");
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "7002", event);
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "7002", event);

        awaitSettlement(7002L);
        verify(settlementService, timeout(20_000).times(2)).settleReturned(eq(7002L), anyLong(), any(), any());
        assertThat(settlementRepository.findAll())
                .filteredOn(s -> s.getSellerOrderId().equals(7002L))
                .hasSize(1);
    }

    @Test
    @DisplayName("sellerBurdenAmount가 없는 SellerOrderReturned는 정산을 부르지 않고 재시도 없이 DLT로 간다")
    void returnedWithoutBurdenGoesToDltWithoutRetry() {
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "7003", envelope("SellerOrderReturned", 7003L,
                Map.of("sellerOrderId", 7003L, "sellerId", 7L)));

        assertThat(pollDlt("7003", Duration.ofSeconds(30))).hasSize(1);
        verify(settlementService, never()).settleReturned(any(), any(), any(), any());
    }

    @Test
    @DisplayName("부담금이 음수인 SellerOrderReturned는 정산을 부르지 않고 재시도 없이 DLT로 간다")
    void returnedWithNegativeBurdenGoesToDltWithoutRetry() {
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "7004", sellerOrderReturnedEnvelope(7004L, "-1"));

        assertThat(pollDlt("7004", Duration.ofSeconds(30))).hasSize(1);
        verify(settlementService, never()).settleReturned(any(), any(), any(), any());
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
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedKafka.container().getBootstrapServers(),
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

    private static String sellerOrderReturnedEnvelope(Long sellerOrderId, String sellerBurdenAmount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sellerOrderId", sellerOrderId);
        payload.put("sellerId", 7L);
        payload.put("sellerBurdenAmount", new BigDecimal(sellerBurdenAmount));
        payload.put("returnedAt", "2026-11-12T10:00:00");
        payload.put("itemSummary", "몬스테라 외 1건");
        return envelope("SellerOrderReturned", sellerOrderId, payload);
    }

    private static String envelope(String eventType, Long sellerOrderId, Map<String, ?> payload) {
        return ENVELOPE_MAPPER.toJson(new EventEnvelope(UUID.randomUUID().toString(), eventType, 1,
                OffsetDateTime.now(), String.valueOf(sellerOrderId), JSON.valueToTree(payload)));
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
