package com.justcommit.backend.product.client.member;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.config.ClockConfig;
import com.justcommit.backend.product.member.application.MemberReplicaService;
import com.justcommit.backend.product.member.domain.MemberReplica;
import com.justcommit.backend.product.member.domain.MemberRole;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 회원 복제본 upsert · 탈퇴 처리 · 복제본 조회를 실제 PostgreSQL로 확인 (ON CONFLICT 쿼리라 DB마다 동작이 다름)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, MemberReplicaService.class, MemberReplicaQuery.class})
@Testcontainers(disabledWithoutDocker = true)
class MemberReplicaIntegrationTest {

    private static final Long SELLER_ID = 7L;
    private static final OffsetDateTime T1 = OffsetDateTime.of(2026, 11, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime T2 = T1.plusMinutes(1);
    private static final OffsetDateTime T3 = T1.plusMinutes(2);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MemberReplicaService memberReplicaService;
    @Autowired MemberQuery memberQuery;
    @Autowired ProductRepository productRepository;
    @Autowired SpeciesRepository speciesRepository;

    private Species species;

    @BeforeEach
    void setUp() {
        species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "name", "회원복제본 테스트 품종"); // V4가 넣은 품종과 이름이 겹치지 않게
        speciesRepository.save(species);
    }

    // ── 복제본 저장 · 판매자 판단 ───────────────────────

    @Test
    @DisplayName("가입 이벤트를 받으면 복제본이 생기고, seller=true면 판매자로 본다")
    void createdSellerIsSeller() {
        assertThat(memberReplicaService.applyProfile(SELLER_ID, "식물집사", MemberRole.SELLER, true, T1)).isTrue();

        assertThat(memberQuery.isSeller(SELLER_ID)).isTrue();
        assertThat(memberQuery.getNickname(SELLER_ID)).isEqualTo("식물집사");
    }

    @Test
    @DisplayName("ADMIN이라도 seller=true면 판매자로 본다 (role이 아니라 seller로 판단)")
    void adminSellerIsSeller() {
        memberReplicaService.applyProfile(SELLER_ID, "관리자판매자", MemberRole.ADMIN, true, T1);

        assertThat(memberQuery.isSeller(SELLER_ID)).isTrue();
    }

    @Test
    @DisplayName("seller=false면 판매자가 아니고, 복제본이 없으면 닉네임은 null")
    void notSellerOrMissing() {
        memberReplicaService.applyProfile(1L, "구매자", MemberRole.MEMBER, false, T1);

        assertThat(memberQuery.isSeller(1L)).isFalse();
        assertThat(memberQuery.isSeller(999L)).isFalse();
        assertThat(memberQuery.getNickname(999L)).isNull();
    }

    @Test
    @DisplayName("더 새 이벤트만 반영하고, 같은 이벤트가 다시 오거나 오래된 이벤트가 늦게 오면 무시한다")
    void appliesOnlyNewerEvents() {
        memberReplicaService.applyProfile(SELLER_ID, "처음", MemberRole.MEMBER, false, T1);
        assertThat(memberReplicaService.applyProfile(SELLER_ID, "판매자됨", MemberRole.SELLER, true, T3)).isTrue();

        assertThat(memberReplicaService.applyProfile(SELLER_ID, "판매자됨", MemberRole.SELLER, true, T3)).isFalse();
        assertThat(memberReplicaService.applyProfile(SELLER_ID, "늦게온옛값", MemberRole.MEMBER, false, T2)).isFalse();

        assertThat(memberQuery.getNickname(SELLER_ID)).isEqualTo("판매자됨");
        assertThat(memberQuery.isSeller(SELLER_ID)).isTrue();
    }

    @Test
    @DisplayName("탈퇴하면 판매자가 아니고 \"탈퇴한 회원\"으로 보이며, 늦게 온 가입 · 변경 이벤트로 되살아나지 않는다")
    void withdrawnIsTerminal() {
        memberReplicaService.applyProfile(SELLER_ID, "식물집사", MemberRole.SELLER, true, T1);
        memberReplicaService.applyWithdrawn(SELLER_ID, T2);

        assertThat(memberReplicaService.applyProfile(SELLER_ID, "되살리기", MemberRole.SELLER, true, T3)).isFalse();

        assertThat(memberQuery.isSeller(SELLER_ID)).isFalse();
        assertThat(memberQuery.getNickname(SELLER_ID)).isEqualTo(MemberReplica.WITHDRAWN_NICKNAME);
    }

    @Test
    @DisplayName("탈퇴 이벤트가 가입 이벤트보다 먼저 와도 탈퇴 상태로 남는다")
    void withdrawnBeforeCreated() {
        memberReplicaService.applyWithdrawn(SELLER_ID, T2);
        memberReplicaService.applyProfile(SELLER_ID, "식물집사", MemberRole.SELLER, true, T1);

        assertThat(memberQuery.isSeller(SELLER_ID)).isFalse();
        assertThat(memberQuery.getNickname(SELLER_ID)).isEqualTo(MemberReplica.WITHDRAWN_NICKNAME);
    }

    // ── 탈퇴한 판매자의 상품 정리 ───────────────────────

    @Test
    @DisplayName("판매자가 탈퇴하면 판매 중(숨김 포함) · 검수 중 · 반려 상품을 삭제하고, 결제 대기 · 판매 완료 · 다른 판매자 상품은 그대로 둔다")
    void withdrawalDeletesOnSaleProductsOnly() {
        Long onSale = saveProduct(SELLER_ID, ProductStatus.ACTIVE, false);
        Long hidden = saveProduct(SELLER_ID, ProductStatus.ACTIVE, true);
        Long inReview = saveProduct(SELLER_ID, ProductStatus.REVIEW_PENDING, false);
        Long rejected = saveProduct(SELLER_ID, ProductStatus.REJECTED, false);
        Long pending = saveProduct(SELLER_ID, ProductStatus.PAYMENT_PENDING, false);
        Long sold = saveProduct(SELLER_ID, ProductStatus.SOLD_OUT, false);
        Long others = saveProduct(8L, ProductStatus.ACTIVE, false);

        assertThat(memberReplicaService.applyWithdrawn(SELLER_ID, T1)).isEqualTo(4);
        // 같은 탈퇴 이벤트가 다시 와도 결과가 같음
        assertThat(memberReplicaService.applyWithdrawn(SELLER_ID, T1)).isZero();

        assertThat(statusOf(onSale)).isEqualTo(ProductStatus.DELETED);
        assertThat(statusOf(hidden)).isEqualTo(ProductStatus.DELETED);
        assertThat(statusOf(inReview)).isEqualTo(ProductStatus.DELETED);
        assertThat(statusOf(rejected)).isEqualTo(ProductStatus.DELETED);
        assertThat(statusOf(pending)).isEqualTo(ProductStatus.PAYMENT_PENDING);
        assertThat(statusOf(sold)).isEqualTo(ProductStatus.SOLD_OUT);
        assertThat(statusOf(others)).isEqualTo(ProductStatus.ACTIVE);
    }

    private Long saveProduct(Long sellerId, ProductStatus status, boolean hidden) {
        Product product = Product.create(species, sellerId, "몬스테라 중품", "설명", new BigDecimal("10000"),
                null, null, null, List.of());
        ReflectionTestUtils.setField(product, "status", status);
        ReflectionTestUtils.setField(product, "hidden", hidden);
        return productRepository.save(product).getId();
    }

    private ProductStatus statusOf(Long productId) {
        return productRepository.findById(productId).orElseThrow().getStatus();
    }
}
