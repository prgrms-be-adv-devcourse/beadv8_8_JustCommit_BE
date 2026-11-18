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
import com.justcommit.backend.product.member.application.MemberReplicaService;
import com.justcommit.backend.product.member.infrastructure.MemberReplicaRepository;
import com.justcommit.backend.product.review.application.ReviewResultPublisher;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.justcommit.backend.common.testcontainers.SharedKafka;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

// 업무 데이터와 Outbox 행이 같은 트랜잭션: 커밋하면 저장 · 발행, 롤백하면 둘 다 없음 (#338 완료 기준)
// payload v2에 바뀐 뒤 상태 · 상품 버전이 담김, 같은 상품의 버전은 1씩 증가 (#456, ADR 0021)
@DataJpaTest(properties = "outbox.scheduler.enabled=false")   // 재전송 스케줄러는 끔 (커밋 직후 발행만 확인)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, ProductService.class, ProductEventPublisher.class,
        ReviewResultPublisher.class, MemberReplicaService.class})
@ImportAutoConfiguration({KafkaAutoConfiguration.class, MessagingConsumerAutoConfiguration.class,
        OutboxAutoConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
// 테스트를 트랜잭션으로 감싸지 않음 → 서비스 트랜잭션이 실제로 커밋 · 롤백됨 (감싸면 afterCommit이 안 불림)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductOutboxIntegrationTest {

    private static final BigDecimal PRICE = new BigDecimal("10000");
    private static final Long SELLER_ID = 1L;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        SharedKafka.register(registry);
    }

    @Autowired ProductUseCase productUseCase;
    @Autowired ProductRepository productRepository;
    @Autowired SpeciesRepository speciesRepository;
    @Autowired ProductReservationRepository reservationRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired EventEnvelopeMapper mapper;
    @Autowired ReviewResultPublisher reviewResultPublisher;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MemberReplicaService memberReplicaService;
    @Autowired MemberReplicaRepository memberReplicaRepository;

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
        memberReplicaRepository.deleteAllInBatch();
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
        assertThat(envelope.version()).isEqualTo(2);
        JsonNode payload = envelope.payload();
        assertThat(payload.get("productId").asLong()).isEqualTo(productId);
        assertThat(payload.get("speciesId").asLong()).isEqualTo(species.getId());
        assertThat(payload.get("sellerId").asLong()).isEqualTo(SELLER_ID);
        assertThat(payload.get("status").asString()).isEqualTo("PAYMENT_PENDING");
        assertThat(payload.get("hidden").asBoolean()).isFalse();
        assertThat(payload.get("productVersion").asLong()).isEqualTo(1L);   // 등록 0 → 예약 1
    }

    @Test
    @DisplayName("예약 → 판매 완료: payload에 바뀐 뒤 상태가 담기고 상품 버전이 1씩 오른다")
    void markSoldOutPublishesNewStatusAndVersion() {
        Long productId = saveActiveProduct();

        productUseCase.reserve(1L, Map.of(productId, PRICE));
        productUseCase.markSoldOut(1L);

        assertThat(payloadsOf(productId))
                .extracting(p -> p.get("status").asString(), p -> p.get("productVersion").asLong())
                .containsExactly(tuple("PAYMENT_PENDING", 1L), tuple("SOLD_OUT", 2L));
        assertThat(productRepository.findById(productId).orElseThrow().getVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("예약 → 예약 해제: payload에 ACTIVE와 올라간 상품 버전이 담긴다")
    void releasePublishesNewStatusAndVersion() {
        Long productId = saveActiveProduct();

        productUseCase.reserve(1L, Map.of(productId, PRICE));
        productUseCase.release(1L);

        assertThat(payloadsOf(productId))
                .extracting(p -> p.get("status").asString(), p -> p.get("productVersion").asLong())
                .containsExactly(tuple("PAYMENT_PENDING", 1L), tuple("ACTIVE", 2L));
    }

    @Test
    @DisplayName("판매자 탈퇴 정리: 삭제된 상품마다 DELETED와 올라간 상품 버전이 담긴다")
    void withdrawalPublishesDeleted() {
        Long first = saveActiveProduct();
        Long second = saveActiveProduct();

        memberReplicaService.applyWithdrawn(SELLER_ID, OffsetDateTime.now());

        for (Long productId : List.of(first, second)) {
            assertThat(payloadsOf(productId))
                    .extracting(p -> p.get("status").asString(), p -> p.get("productVersion").asLong())
                    .containsExactly(tuple("DELETED", 1L));
        }
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
        // 상품 버전도 롤백되어 그대로
        assertThat(productRepository.findById(productId).orElseThrow().getVersion()).isZero();
    }

    // ── 검수 결과 이벤트 (#345) ───────────────────

    @Test
    @DisplayName("검수 반려가 커밋되면 ProductStatusChanged와 ProductRejected(사유 코드 · 판매자 문구)가 Outbox에 저장 · 발행된다")
    void reviewRejectedPublishes() {
        Long productId = saveActiveProduct();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                reviewResultPublisher.rejected(productId, 7L, 2, "몬스테라 중품", RejectReason.HARMFUL_IMAGE, null));

        assertThat(outboxEventRepository.findAll()).extracting(row -> row.getEventType())
                .containsExactlyInAnyOrder("ProductStatusChanged", "ProductRejected");

        EventEnvelope envelope = pollEvent(KafkaTopics.PRODUCT_EVENTS, String.valueOf(productId), "ProductRejected");
        assertThat(envelope.payload().get("productId").asLong()).isEqualTo(productId);
        assertThat(envelope.payload().get("sellerId").asLong()).isEqualTo(7L);
        assertThat(envelope.payload().get("reviewVersion").asInt()).isEqualTo(2);
        assertThat(envelope.payload().get("productTitle").asString()).isEqualTo("몬스테라 중품");
        assertThat(envelope.payload().get("reasonCode").asString()).isEqualTo("HARMFUL_IMAGE");
        assertThat(envelope.payload().get("reasonMessage").asString()).isEqualTo("부적절한 사진이 포함돼 있어요");
    }

    @Test
    @DisplayName("관리자가 OTHER로 반려하면 판매자 문구는 관리자가 입력한 내용 (#344에서 사용)")
    void reviewRejectedOtherUsesMemo() {
        Long productId = saveActiveProduct();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                reviewResultPublisher.rejected(productId, 7L, 0, "몬스테라", RejectReason.OTHER, "가격 표기가 사진과 달라요"));

        EventEnvelope envelope = pollEvent(KafkaTopics.PRODUCT_EVENTS, String.valueOf(productId), "ProductRejected");
        assertThat(envelope.payload().get("reasonCode").asString()).isEqualTo("OTHER");
        assertThat(envelope.payload().get("reasonMessage").asString()).isEqualTo("가격 표기가 사진과 달라요");
    }

    @Test
    @DisplayName("검수 통과 반영이 롤백되면 결과 이벤트 Outbox 행도 남지 않는다")
    void reviewResultRollbackLeavesNoRow() {
        Long productId = saveActiveProduct();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            reviewResultPublisher.approved(productId, 7L, 0, "몬스테라");
            throw new IllegalStateException("반영 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("트랜잭션 밖에서 결과 이벤트를 보내면 막힘 (상태 반영과 같은 트랜잭션에서만)")
    void reviewResultRequiresTransaction() {
        assertThatThrownBy(() -> reviewResultPublisher.approved(903L, 7L, 0, "몬스테라"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    // 같은 Key로 여러 이벤트가 오므로 이벤트 이름까지 골라 기다림
    private EventEnvelope pollEvent(String topic, String key, String eventType) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedKafka.container().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(r.key())) {
                        EventEnvelope envelope = mapper.fromJson(r.value());
                        if (eventType.equals(envelope.eventType())) {
                            return envelope;
                        }
                    }
                }
            }
        }
        throw new AssertionError(eventType + " 이벤트가 오지 않음: key=" + key);
    }

    // 이 상품의 Outbox 행 payload, 상품 버전 순
    private List<JsonNode> payloadsOf(Long productId) {
        return outboxEventRepository.findAll().stream()
                .filter(row -> row.getAggregateId().equals(String.valueOf(productId)))
                .map(row -> mapper.fromJson(row.getPayload()).payload())
                .sorted(Comparator.comparingLong(p -> p.get("productVersion").asLong()))
                .toList();
    }

    private Long saveActiveProduct() {
        Product product = Product.create(species, SELLER_ID, "몬스테라 중품", "설명", PRICE,
                null, null, null, List.of());
        ReflectionTestUtils.setField(product, "status", ProductStatus.ACTIVE);
        return productRepository.save(product).getId();
    }

    // 다른 테스트의 메시지와 섞이지 않게 Key(상품 ID)로 골라 하나를 기다림 (최대 30초)
    private ConsumerRecord<String, String> pollOne(String topic, String key) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedKafka.container().getBootstrapServers(),
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
