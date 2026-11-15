package com.justcommit.backend.product.messaging;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.common.messaging.consumer.MessagingConsumerAutoConfiguration;
import com.justcommit.backend.common.messaging.outbox.OutboxAutoConfiguration;
import com.justcommit.backend.common.messaging.outbox.OutboxEventRepository;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.banned.application.BannedListService;
import com.justcommit.backend.product.config.ClockConfig;
import com.justcommit.backend.product.image.application.ImageData;
import com.justcommit.backend.product.image.application.ImageReader;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.domain.ReviewScope;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.review.application.ImageReviewService;
import com.justcommit.backend.product.review.application.ModerationClient;
import com.justcommit.backend.product.review.application.PlantIdentifier;
import com.justcommit.backend.product.review.application.ReviewJudgeProvider;
import com.justcommit.backend.product.review.application.ReviewResultPublisher;
import com.justcommit.backend.product.review.domain.Candidate;
import com.justcommit.backend.product.review.domain.ImageReview;
import com.justcommit.backend.product.review.domain.PlantIdentification;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
import com.justcommit.backend.product.review.domain.enums.ReviewStatus;
import com.justcommit.backend.product.review.domain.enums.SaleForm;
import com.justcommit.backend.product.review.infrastructure.ImageReviewRepository;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 등록 → Outbox → Kafka(product.review.v1) → 검수 리스너 → 검수 → 결과 반영을 실제 PostgreSQL · Kafka로 한 번에 확인 (#345 완료 기준)
// 외부 API(사진 읽기 · Moderation · Haiku)만 가짜
@DataJpaTest(properties = "outbox.scheduler.enabled=false")   // 재전송 스케줄러는 끔 (커밋 직후 발행만으로 흐름이 이어지는지 확인)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, ImageReviewService.class, ReviewJudgeProvider.class,
        ReviewResultPublisher.class, BannedListService.class, ProductReviewListener.class,
        ProductReviewKafkaIntegrationTest.Config.class})
@ImportAutoConfiguration({KafkaAutoConfiguration.class, MessagingConsumerAutoConfiguration.class,
        OutboxAutoConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
// 등록 트랜잭션이 실제로 커밋돼야 Outbox가 발행됨 (테스트를 트랜잭션으로 감싸지 않음)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductReviewKafkaIntegrationTest {

    private static final Long SELLER_ID = 7L;
    private static final String MONSTERA = "Monstera deliciosa";

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

    @TestConfiguration
    static class Config {
        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired ImageReviewService imageReviewService;
    @Autowired ImageReviewRepository imageReviewRepository;
    @Autowired ProductRepository productRepository;
    @Autowired SpeciesRepository speciesRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean ImageReader imageReader;
    @MockitoBean ModerationClient moderationClient;
    @MockitoBean PlantIdentifier plantIdentifier;

    private Species species;

    @BeforeEach
    void setUp() {
        species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "name", "Kafka 검수 테스트 품종");
        ReflectionTestUtils.setField(species, "scientificName", MONSTERA);
        speciesRepository.save(species);

        given(imageReader.read(anyString())).willReturn(new ImageData(new byte[]{1}, "image/jpeg"));
        given(moderationClient.isImageFlagged(any())).willReturn(false);
        given(moderationClient.isTextFlagged(anyString())).willReturn(false);
        given(plantIdentifier.identify(anyList(), anyList())).willReturn(
                new PlantIdentification(true, SaleForm.PLANT, List.of(new Candidate(MONSTERA, null, 0.95))));
    }

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAllInBatch();
        imageReviewRepository.deleteAllInBatch();
        jdbc.update("delete from product_image");
        productRepository.deleteAllInBatch();
        speciesRepository.deleteById(species.getId());
    }

    @Test
    @DisplayName("등록이 커밋되면 검수 요청이 Kafka를 거쳐 리스너로 가고, 검수 통과가 상품에 반영된다")
    void registerToReviewThroughKafka() throws InterruptedException {
        // 등록처럼: 상품 저장 + 검수 요청(검수 기록 · Outbox)을 한 트랜잭션에서
        Product product = tx.execute(status -> {
            Product saved = productRepository.save(Product.create(species, SELLER_ID, "몬스테라 팝니다", "잘 키웠어요",
                    new BigDecimal("10000"), null, null, null, List.of("http://localhost:9000/bucket/products/7/a.jpg")));
            imageReviewService.request(saved, ReviewScope.FULL);
            return saved;
        });

        // 커밋 직후 Outbox → product.review.v1 → 검수 리스너 → 검수 → 결과 반영
        ImageReview review = waitUntilReviewed(product.getId());

        assertThat(review.getDecision()).isEqualTo(ReviewDecision.APPROVED);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.ACTIVE);
        verify(plantIdentifier, times(1)).identify(anyList(), anyList());
        // 검수 요청 · 상태 변경 · 통과 알림이 모두 Outbox를 거침
        assertThat(outboxEventRepository.findAll())
                .extracting(row -> row.getTopic() + "/" + row.getEventType())
                .contains(KafkaTopics.PRODUCT_REVIEW + "/ProductReviewRequested",
                        KafkaTopics.PRODUCT_EVENTS + "/ProductStatusChanged",
                        KafkaTopics.PRODUCT_EVENTS + "/ProductApproved");
    }

    // 리스너가 검수를 끝낼 때까지 기다림 (컨슈머 그룹 참여 · 토픽 생성 시간 포함)
    private ImageReview waitUntilReviewed(Long productId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            ImageReview review = imageReviewRepository.findByProductIdAndReviewVersion(productId, 0).orElseThrow();
            if (review.getStatus() == ReviewStatus.DONE) {
                return review;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("60초 안에 Kafka 검수 리스너가 검수를 끝내지 않음");
    }
}
