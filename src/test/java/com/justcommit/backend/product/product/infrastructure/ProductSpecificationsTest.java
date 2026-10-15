package com.justcommit.backend.product.product.infrastructure;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.species.domain.Species;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test") //application-test.yml 설정 사용
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Testcontainers(disabledWithoutDocker = true) //테스트할 때 Docker로 진짜 DB 컨테이너를 띄워 주는 라이브러리
class ProductSpecificationsTest {

    @Container //테스트 전용 Postgres 컨테이너
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final Long SELLER_ID = 1L;
    private static final Long OTHER_SELLER_ID = 2L;
    private static final PageRequest LATEST = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ProductRepository productRepository;

    private Product active, hidden, soldOut, deleted, others;

    @BeforeEach
    void setUp() {
        Species species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "name", "몬스테라");
        entityManager.persist(species);

        active  = save(species, SELLER_ID, "판매 중");
        hidden  = save(species, SELLER_ID, "숨김");
        soldOut = save(species, SELLER_ID, "판매 완료");
        deleted = save(species, SELLER_ID, "삭제");
        others  = save(species, OTHER_SELLER_ID, "남의 상품");

        // 상태 바꿈
        hidden.changeHidden(true);
        ReflectionTestUtils.setField(soldOut, "status", ProductStatus.SOLD_OUT);
        deleted.delete();
        entityManager.flush();   // 상태 변경을 먼저 DB에 반영

        // 최신순 검증을 위한 생성 시각 조정 (숫자가 클수록 오래된 상품)
        setCreatedAt(active, 1);
        setCreatedAt(hidden, 2);
        setCreatedAt(soldOut, 3);
        setCreatedAt(deleted, 4);
        setCreatedAt(others, 5);
        entityManager.clear();   // 메모리의 엔티티를 비워서 다시 덮어쓰지 않게 함
    }

    // 상품 하나 저장
    private Product save(Species species, Long sellerId, String title) {
        Product product = Product.create(species, sellerId, title, "설명",
                new BigDecimal("10000"), null, null, null, List.of());
        entityManager.persist(product);
        return product;
    }

    // SQL 통해 생성 시각 바꿈 - createdAt 은 Auditing 이 관리하는 필드라 엔티티로 바꾸기 어려워 직접 DB 수정
    private void setCreatedAt(Product product, int minutesAgo) {
        entityManager.createNativeQuery("UPDATE product SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", LocalDateTime.now().minusMinutes(minutesAgo))
                .setParameter("id", product.getId())
                .executeUpdate();
    }

    // 삼품 객체 전체 비교 번거로우니 대신 ID 목록으로 비교
    private List<Long> idsOf(Page<Product> page) {
        return page.getContent().stream().map(Product::getId).toList();
    }

    @Test
    @DisplayName("상태 필터가 없으면 숨긴 상품은 포함하고 삭제·남의 상품은 빼고 최신순으로 조회")
    void sellerConditionWithoutStatus() {
        // when: 판매자 1번, 상태 필터 없음, 최신순 조회
        Page<Product> result = productRepository.findAll(
                ProductSpecifications.sellerCondition(SELLER_ID, null), LATEST);

        // 숨긴 상품 포함/ 삭제 상품 제외/ 남의 상품 제외 / 최신순 인지
        assertThat(idsOf(result))
                .containsExactly(active.getId(), hidden.getId(), soldOut.getId());
    }

    @Test
    @DisplayName("상태 필터를 주면 해당 상태만 조회")
    void sellerConditionWithStatus() {
        // 상태 필터 SOLD_OUT 일 떄 추가
        Page<Product> result = productRepository.findAll(
                ProductSpecifications.sellerCondition(SELLER_ID, ProductStatus.SOLD_OUT), LATEST);

        // 판매 완료 상품만 조회되도록
        assertThat(idsOf(result)).containsExactly(soldOut.getId());
    }

    @Test
    @DisplayName("DELETED로 필터해도 삭제된 상품은 나오지 않고 필터가 무시됨")
    void sellerConditionWithDeletedStatus() {

        //판매자라고 하더라도 DELETED 로 조회시 삭제된 상품은 나오지 않도록 함
        Page<Product> result = productRepository.findAll(
                ProductSpecifications.sellerCondition(SELLER_ID, ProductStatus.DELETED), LATEST);

        // 필터가 무시되었으니, 3개 (status 상관없이) 가 나와야함
        assertThat(idsOf(result)).doesNotContain(deleted.getId()).hasSize(3);
    }
}