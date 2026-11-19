package com.justcommit.backend.notification.infrastructure.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.common.testcontainers.SharedKafka;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.notification.domain.Notification;
import com.justcommit.backend.notification.domain.NotificationType;
import com.justcommit.backend.notification.domain.RecipientRole;
import com.justcommit.backend.notification.infrastructure.repository.NotificationRepository;
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
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 Kafka · PostgreSQL로 서비스별 토픽(봉투) 수신 → eventType으로 고른 알림 저장을 확인 (#561)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
// 리스너가 공유 Kafka의 다른 클래스 메시지를 읽지 않게 끝나면 컨텍스트를 닫는다
@DirtiesContext
class NotificationKafkaIntegrationTest {

    private static final EventEnvelopeMapper ENVELOPE_MAPPER = new EventEnvelopeMapper();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long SELLER = 20L;
    private static final long BUYER = 10L;

    // 운영 기본값은 리스너 꺼짐 · 처음 붙을 때 최신부터라, 테스트에서는 켜고 앞에 보낸 메시지부터 읽게 함
    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        SharedKafka.register(registry);
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("jwt.public-key", NotificationKafkaIntegrationTest::newPublicKey);
    }

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired NotificationRepository notificationRepository;

    @Test
    @DisplayName("market.events.v1의 SellerOrderPaid 봉투를 받으면 판매자에게 수락 요청 알림이 생긴다")
    void createsSellerOrderPaidNotification() {
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "5101", envelope("SellerOrderPaid", "5101",
                Map.of("sellerOrderId", 5101L, "sellerId", SELLER, "buyerId", BUYER, "paidAt", "2026-11-19T10:00:00")));

        Notification notification = await("SELLER_ORDER_PAID:5101");
        assertThat(notification.getMemberId()).isEqualTo(SELLER);
        assertThat(notification.getType()).isEqualTo(NotificationType.SELLER_ORDER_PAID);
        assertThat(notification.getSellerOrderId()).isEqualTo(5101L);
    }

    @Test
    @DisplayName("[S-H02] 같은 배송 이벤트를 두 번 받아도 구매자 · 판매자 알림은 한 건씩이다")
    void sameEventTwiceCreatesOnce() {
        String event = envelope("ShipmentStatusChanged", "5201", shipmentPayload(5201L));
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "5201", event);
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "5201", event);
        // 같은 Key라 같은 파티션에서 차례로 처리: 뒤에 보낸 표시 이벤트가 저장되면 앞의 두 번도 처리된 것
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "5201", envelope("SellerOrderPaid", "5201",
                Map.of("sellerOrderId", 5201L, "sellerId", SELLER, "buyerId", BUYER, "paidAt", "2026-11-19T10:00:00")));

        await("SELLER_ORDER_PAID:5201");
        assertThat(notificationRepository.findAll())
                .filteredOn(n -> n.getEventKey().startsWith("SHIPMENT:5201:"))
                .extracting(Notification::getEventKey)
                .containsExactlyInAnyOrder("SHIPMENT:5201:PICKED_UP", "SHIPMENT:5201:PICKED_UP:SELLER");
    }

    @Test
    @DisplayName("product.events.v1의 ProductRejected는 판매자에게 사유와 상품 id가 담긴 알림을 만든다")
    void createsProductRejectedNotification() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", 5301L);
        payload.put("sellerId", SELLER);
        payload.put("reviewVersion", 1);
        payload.put("productTitle", "몬스테라 알보");
        payload.put("reasonCode", "HARMFUL_IMAGE");
        payload.put("reasonMessage", "사진이 정책에 맞지 않아요.");
        kafkaTemplate.send(KafkaTopics.PRODUCT_EVENTS, "5301", envelope("ProductRejected", "5301", payload));

        Notification notification = await("PRODUCT_REJECTED:5301:1");
        assertThat(notification.getMemberId()).isEqualTo(SELLER);
        assertThat(notification.getProductId()).isEqualTo(5301L);
        assertThat(notification.getItemSummary()).isEqualTo("몬스테라 알보");
        assertThat(notification.getMessage()).isEqualTo("검수에서 반려됐어요. 사유: 사진이 정책에 맞지 않아요.");
    }

    @Test
    @DisplayName("[S-H02] ImageReviewDelayed는 같은 eventId로 두 번 와도 관리자 역할 알림 1건이다")
    void createsAdminReviewDelayedOnce() {
        String eventId = UUID.randomUUID().toString();
        String event = ENVELOPE_MAPPER.toJson(new EventEnvelope(eventId, "ImageReviewDelayed", 1, OffsetDateTime.now(),
                "image-review-delayed", JSON.valueToTree(Map.of("delayedCount", 4, "checkedAt", "2026-11-19T14:00:00"))));
        kafkaTemplate.send(KafkaTopics.PRODUCT_EVENTS, "image-review-delayed", event);
        kafkaTemplate.send(KafkaTopics.PRODUCT_EVENTS, "image-review-delayed", event);
        // 같은 Key라 차례로 처리: 뒤의 표시 이벤트가 보이면 앞의 두 번도 처리된 것
        kafkaTemplate.send(KafkaTopics.PRODUCT_EVENTS, "image-review-delayed", envelope("ProductApproved",
                "image-review-delayed", Map.of("productId", 5601L, "sellerId", SELLER, "reviewVersion", 1,
                        "productTitle", "표시")));

        await("PRODUCT_APPROVED:5601:1");
        assertThat(notificationRepository.findAll())
                .filteredOn(n -> n.getEventKey().equals("ADMIN_REVIEW_DELAYED:" + eventId))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.getRecipientRole()).isEqualTo(RecipientRole.ADMIN);
                    assertThat(n.getMemberId()).isNull();
                    assertThat(n.getMessage()).isEqualTo("검수 확인이 늦어진 상품이 4건 있어요. 24시간이 지나면 자동 반려돼요.");
                });
    }

    @Test
    @DisplayName("알림과 상관없는 eventType(상품 상태 동기화)은 예외나 DLT 없이 건너뛰고 뒤 이벤트를 처리한다")
    void skipsOtherEventTypesWithoutDlt() {
        kafkaTemplate.send(KafkaTopics.PRODUCT_EVENTS, "5401", envelope("ProductStatusChanged", "5401",
                Map.of("productId", 5401L, "status", "ON_SALE")));
        kafkaTemplate.send(KafkaTopics.PRODUCT_EVENTS, "5401", envelope("ProductApproved", "5401",
                Map.of("productId", 5401L, "sellerId", SELLER, "reviewVersion", 1, "productTitle", "몬스테라")));

        Notification notification = await("PRODUCT_APPROVED:5401:1");
        assertThat(notification.getMessage()).isEqualTo(NotificationType.PRODUCT_APPROVED.getMessage());
        assertThat(pollDlt(KafkaTopics.PRODUCT_EVENTS + ".DLT", "5401", Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("[S-H04] 형식이 깨진 메시지는 재시도 없이 market.events.v1.DLT로 간다")
    void malformedMessageGoesToDlt() {
        kafkaTemplate.send(KafkaTopics.MARKET_EVENTS, "5501", "not-json");

        List<ConsumerRecord<String, String>> dlt = pollDlt(KafkaTopics.MARKET_EVENTS + ".DLT", "5501",
                Duration.ofSeconds(30));
        assertThat(dlt).singleElement().satisfies(r -> assertThat(r.value()).isEqualTo("not-json"));
    }

    private Notification await(String eventKey) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<Notification> found = notificationRepository.findAll().stream()
                    .filter(n -> n.getEventKey().equals(eventKey))
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            sleep();
        }
        throw new AssertionError("알림이 생기지 않음: eventKey=" + eventKey);
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
    private List<ConsumerRecord<String, String>> pollDlt(String topic, String key, Duration wait) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedKafka.container().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
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

    private static Map<String, Object> shipmentPayload(Long sellerOrderId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("shipmentId", sellerOrderId);
        payload.put("orderId", 1L);
        payload.put("sellerOrderId", sellerOrderId);
        payload.put("buyerId", BUYER);
        payload.put("sellerId", SELLER);
        payload.put("status", "PICKED_UP");
        payload.put("occurredAt", "2026-11-19T10:00:00");
        payload.put("itemSummary", "몬스테라 외 1건");
        return payload;
    }

    private static String envelope(String eventType, String aggregateId, Map<String, ?> payload) {
        return ENVELOPE_MAPPER.toJson(new EventEnvelope(UUID.randomUUID().toString(), eventType, 1,
                OffsetDateTime.now(), aggregateId, JSON.valueToTree(payload)));
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
