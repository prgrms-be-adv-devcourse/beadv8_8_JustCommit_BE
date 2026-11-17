package com.justcommit.backend.product.product.application;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 상품 버전: 상품 행을 바꾸는 모든 경로에서 UPDATE 한 번에 +1 (#444, ADR 0021). DB 값을 직접 읽어 확인
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, ProductService.class, ProductEventPublisher.class, ProductVersionIntegrationTest.Config.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductVersionIntegrationTest {

    private static final BigDecimal PRICE = new BigDecimal("10000");
    private static final Long SELLER_ID = 7L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class Config {
        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }
    }

    @Autowired ProductUseCase productUseCase;
    @Autowired ProductRepository productRepository;
    @Autowired ProductReservationRepository reservationRepository;
    @Autowired SpeciesRepository speciesRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean MemberQuery memberQuery;
    @MockitoBean ProductImageUrlValidator imageUrlValidator;
    @MockitoBean ProductImageCleaner imageCleaner;
    @MockitoBean ReviewRequester reviewRequester;
    @MockitoBean OutboxPublisher outboxPublisher;

    private Species species;

    @BeforeEach
    void setUp() {
        species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "name", "상품 버전 테스트 품종");
        speciesRepository.save(species);
    }

    @AfterEach
    void cleanUp() {
        reservationRepository.deleteAllInBatch();
        jdbc.update("delete from product_image");
        productRepository.deleteAllInBatch();
        speciesRepository.deleteById(species.getId());
    }

    private Long saveActiveProduct() {
        Product product = Product.create(species, SELLER_ID, "몬스테라", "설명", PRICE, null, null, null, List.of());
        ReflectionTestUtils.setField(product, "status", ProductStatus.ACTIVE);
        return productRepository.save(product).getId();
    }

    // 영속성 컨텍스트를 거치지 않고 DB 값을 읽음
    private long versionOf(Long productId) {
        return jdbc.queryForObject("select version from product where id = ?", Long.class, productId);
    }

    @Test
    @DisplayName("[#444] 등록은 0, 수정(여러 칸이어도 한 번) · 검수 반영 · 숨김 · 예약 · 해제 · 판매 완료 · 판매 재개마다 1씩 증가")
    void increasesByOneOnEveryChange() {
        Long productId = saveActiveProduct();
        assertThat(versionOf(productId)).isZero();

        // 엔티티 수정: 한 트랜잭션에서 여러 칸을 바꿔도 UPDATE는 한 번이라 +1
        tx.executeWithoutResult(status -> {
            Product product = productRepository.findWithLockById(productId).orElseThrow();
            product.update(species, "몬스테라 대품", "새 설명", new BigDecimal("12000"), 80, null, null, List.of());
        });
        assertThat(versionOf(productId)).isEqualTo(1);
        // 판매 중 상품의 글만 고쳤으니 글 검사 통과 반영: 상태는 그대로 ACTIVE여도 UPDATE라 +1
        tx.executeWithoutResult(status -> productRepository.applyReviewResult(productId, 1,
                ProductStatus.ACTIVE, ProductStatus.ACTIVE));
        assertThat(versionOf(productId)).as("검수 반영 뒤").isEqualTo(2);
        tx.executeWithoutResult(status -> productRepository.findWithLockById(productId).orElseThrow().changeHidden(true));
        assertThat(versionOf(productId)).as("숨김 뒤").isEqualTo(3);
        tx.executeWithoutResult(status -> productRepository.findWithLockById(productId).orElseThrow().changeHidden(false));
        assertThat(versionOf(productId)).isEqualTo(4);

        productUseCase.reserve(1L, Map.of(productId, new BigDecimal("12000")));   // 일괄 UPDATE: 예약
        assertThat(versionOf(productId)).isEqualTo(5);
        productUseCase.release(1L);                                     // 일괄 UPDATE: 예약 해제
        assertThat(versionOf(productId)).isEqualTo(6);
        productUseCase.reserve(2L, Map.of(productId, new BigDecimal("12000")));
        assertThat(versionOf(productId)).isEqualTo(7);
        productUseCase.markSoldOut(2L);                                 // 일괄 UPDATE: 판매 완료
        assertThat(versionOf(productId)).isEqualTo(8);
        productUseCase.releaseItem(productId, 2L, false);               // 일괄 UPDATE: 주문상품 판매 재개
        assertThat(versionOf(productId)).isEqualTo(9);
    }

    @Test
    @DisplayName("[#444] 사진 검수 결과 반영 · 판매자 탈퇴 정리도 1씩, 조건이 안 맞아 0건이면 그대로")
    void reviewResultAndSellerWithdrawal() {
        Long productId = saveActiveProduct();

        // 판매 중 → 검수 중(글 검사 관리자 확인과 같은 경로)
        int applied = tx.execute(status -> productRepository.applyReviewResult(productId, 0,
                ProductStatus.ACTIVE, ProductStatus.REVIEW_PENDING));
        assertThat(applied).isEqualTo(1);
        assertThat(versionOf(productId)).isEqualTo(1);

        // 검수 버전이 달라 반영하지 않으면 버전도 그대로
        int stale = tx.execute(status -> productRepository.applyReviewResult(productId, 9,
                ProductStatus.REVIEW_PENDING, ProductStatus.ACTIVE));
        assertThat(stale).isZero();
        assertThat(versionOf(productId)).isEqualTo(1);

        tx.executeWithoutResult(status -> productRepository.deleteOnSaleBySeller(SELLER_ID));
        assertThat(versionOf(productId)).isEqualTo(2);
    }
}
