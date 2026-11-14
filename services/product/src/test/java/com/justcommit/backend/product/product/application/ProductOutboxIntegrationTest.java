package com.justcommit.backend.product.product.application;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.common.messaging.consumer.MessagingConsumerAutoConfiguration;
import com.justcommit.backend.common.messaging.outbox.OutboxAutoConfiguration;
import com.justcommit.backend.common.messaging.outbox.OutboxEventRepository;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.ProductUseCase;
import com.justcommit.backend.product.client.member.MemberQuery;
import com.justcommit.backend.product.config.ClockConfig;
import com.justcommit.backend.product.image.application.ProductImageCleaner;
import com.justcommit.backend.product.image.application.ProductImageUrlValidator;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.reservation.infrastructure.ProductReservationRepository;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 업무 데이터와 Outbox 행이 같은 트랜잭션: 커밋하면 저장 · 발행, 롤백하면 둘 다 없음 (#338 완료 기준)
@DataJpaTest(properties = "outbox.scheduler.enabled=false")   // 재전송 스케줄러는 끔 (커밋 직후 발행만 확인)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, ProductService.class})
@ImportAutoConfiguration({KafkaAutoConfiguration.class, MessagingConsumerAutoConfiguration.class,
        OutboxAutoConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
// 테스트를 트랜잭션으로 감싸지 않음 → 서비스 트랜잭션이 실제로 커밋 · 롤백됨 (감싸면 afterCommit이 안 불림)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductOutboxIntegrationTest {

    private static final BigDecimal PRICE = new BigDecimal("10000");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired ProductUseCase productUseCase;
    @Autowired ProductRepository productRepository;
    @Autowired SpeciesRepository speciesRepository;
    @Autowired ProductReservationRepository reservationRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired EventEnvelopeMapper mapper;

    // 예약 흐름에서 쓰지 않는 ProductService 의존성
    @MockitoBean MemberQuery memberQuery;
    @MockitoBean ProductImageUrlValidator imageUrlValidator;
    // 사진 검수는 ImageReviewServiceIntegrationTest에서
    @MockitoBean ReviewRequester reviewRequester;
    @MockitoBean ProductImageCleaner imageCleaner;

    private Species species;

    @BeforeEach
    void setUp() {
        species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "name", "테스트 품종");
        speciesRepository.save(species);
    }

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        speciesRepository.deleteById(species.getId());
        reservationRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("예약이 커밋되면 Outbox에 ProductStatusChanged가 저장되고, product.events.v1로 봉투 형식 발행된다")
    void publishesAfterCommit() {
        Long productId = saveActiveProduct();

        productUseCase.reserve(1L, Map.of(productId, PRICE));

        // 같은 트랜잭션: 상품 상태와 Outbox 행이 함께 커밋됨
        assertThat(productRepository.findById(productId).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.PAYMENT_PENDING);
        assertThat(outboxEventRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getTopic()).isEqualTo(KafkaTopics.PRODUCT_EVENTS);
            assertThat(row.getEventType()).isEqualTo("ProductStatusChanged");
            assertThat(row.getAggregateId()).isEqualTo(String.valueOf(productId));
        });

        // 커밋 뒤 Kafka: Key = 상품 ID, 값 = 봉투
        ConsumerRecord<String, String> record = pollOne(KafkaTopics.PRODUCT_EVENTS, String.valueOf(productId));
        EventEnvelope envelope = mapper.fromJson(record.value());
        assertThat(envelope.eventType()).isEqualTo("ProductStatusChanged");
        assertThat(envelope.aggregateId()).isEqualTo(String.valueOf(productId));
        assertThat(envelope.payload().get("productId").asLong()).isEqualTo(productId);
    }

    @Test
    @DisplayName("가격이 달라 예약이 롤백되면 상품도 그대로이고 Outbox 행도 남지 않는다")
    void rollbackLeavesNoOutboxRow() {
        Long productId = saveActiveProduct();

        assertThatThrownBy(() -> productUseCase.reserve(2L, Map.of(productId, new BigDecimal("9000"))))
                .isInstanceOf(BusinessException.class);

        assertThat(productRepository.findById(productId).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.ACTIVE);
        assertThat(outboxEventRepository.count()).isZero();
    }

    private Long saveActiveProduct() {
        Product product = Product.create(species, 1L, "몬스테라 중품", "설명", PRICE,
                null, null, null, List.of());
        ReflectionTestUtils.setField(product, "status", ProductStatus.ACTIVE);
        return productRepository.save(product).getId();
    }

    // 다른 테스트의 메시지와 섞이지 않게 Key(상품 ID)로 골라 하나를 기다림 (최대 30초)
    private ConsumerRecord<String, String> pollOne(String topic, String key) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                var found = StreamSupport.stream(consumer.poll(Duration.ofMillis(500)).spliterator(), false)
                        .filter(r -> key.equals(r.key()))
                        .findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            }
        }
        throw new AssertionError("product.events.v1에서 key=" + key + " 메시지를 30초 안에 받지 못함");
    }
}
