package com.justcommit.backend.product.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.config.ClockConfig;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.product.presentation.dto.ProductSearchRequest;
import com.justcommit.backend.product.product.presentation.dto.ProductSearchResponse;
import com.justcommit.backend.product.search.application.ProductIndexService;
import com.justcommit.backend.product.search.application.ProductSearchService;
import com.justcommit.backend.product.search.infrastructure.OpenSearchConfig;
import com.justcommit.backend.product.search.infrastructure.OpenSearchProductIndex;
import com.justcommit.backend.product.search.infrastructure.OpenSearchProductSearch;
import com.justcommit.backend.product.search.infrastructure.ProductIndexInitializer;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 상품 목록 검색 (#486): DB에 상품을 넣고 전체 색인으로 OpenSearch에 채운 뒤 {@link ProductSearchService}로 찾는다.
 * PostgreSQL · OpenSearch(nori 이미지) 모두 실제 컨테이너. 이슈 #486의 완료 기준 4개를 테스트 하나씩으로 확인한다.
 *
 * <p>테스트를 트랜잭션으로 감싸지 않는다: 운영처럼 서비스가 스스로 연 트랜잭션에서 읽어야
 * (open-in-view 꺼짐) 썸네일 지연 로딩 같은 문제가 테스트에서도 드러남. 상품은 @AfterEach에서 지우고, 인덱스는 @BeforeEach에서 새로 만든다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, ProductIndexService.class, OpenSearchProductIndex.class,
        OpenSearchProductSearch.class, ProductSearchService.class, ProductSearchIntegrationTest.Config.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductSearchIntegrationTest {

    private static final Long SELLER_ID = 4860L;
    private static final Long OTHER_SELLER_ID = 4861L;
    // V4 시드 품종
    private static final Long MONSTERA_ID = 62L;
    private static final Long SCINDAPSUS_ID = 111L;
    private static final Long SANSEVIERIA_ID = 93L;
    private static final String INDEX_V1 = "products_v1";
    private static final Path DOCKERFILE_DIR = Path.of("../../docker/opensearch").toAbsolutePath().normalize();

    // 로컬 compose · 운영과 같은 docker/opensearch/Dockerfile(공식 이미지 + nori)
    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            new ImageFromDockerfile("replant-opensearch-test", false).withFileFromPath(".", DOCKERFILE_DIR))
            .withEnv("discovery.type", "single-node")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @TestConfiguration
    static class Config {
        // 운영 설정과 같은 클래스로 만들되 주소만 컨테이너로, 읽기 타임아웃은 느린 CI를 위해 넉넉히
        @Bean
        OpenSearchClient openSearchClient() throws URISyntaxException {
            return client(Duration.ofSeconds(10));
        }
    }

    private static OpenSearchClient client(Duration readTimeout) throws URISyntaxException {
        OpenSearchConfig config = new OpenSearchConfig(
                "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200),
                Duration.ofSeconds(1), readTimeout);
        return config.openSearchClient(config.openSearchTransport());
    }

    @Autowired OpenSearchClient client;
    @Autowired ProductSearchService searchService;
    @Autowired ProductIndexService indexService;
    @Autowired ProductRepository productRepository;
    @Autowired SpeciesRepository speciesRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from product_image");
        productRepository.deleteAllInBatch();
    }

    @BeforeEach
    void recreateIndex() throws IOException {
        if (client.indices().exists(request -> request.index(INDEX_V1)).value()) {
            client.indices().delete(request -> request.index(INDEX_V1));
        }
        new ProductIndexInitializer(client).initialize();
    }

    @Test
    @DisplayName("[완료 기준 1] \"몬 스테라\"로 찾으면 몬스테라 상품이 관련도순 (제목 > 품종명 > 설명)")
    void spacedKeywordFindsByRelevance() throws IOException {
        Product inTitleAndSpecies = save(MONSTERA_ID, SELLER_ID, "몬스테라 알보 팝니다", "잘 키웠어요", ProductStatus.ACTIVE);
        Product inSpeciesOnly = save(MONSTERA_ID, SELLER_ID, "거실 대형 화분", "잘 키웠어요", ProductStatus.ACTIVE);
        Product inDescriptionOnly = save(SCINDAPSUS_ID, SELLER_ID, "덩굴 식물", "몬스테라 옆에서 키웠어요", ProductStatus.ACTIVE);
        save(SANSEVIERIA_ID, SELLER_ID, "산세베리아 분양", "잘 키웠어요", ProductStatus.ACTIVE);
        index();

        // 띄어쓰기를 무시해 찾고, 제목 · 품종명 둘 다 맞는 상품이 맨 앞
        assertThat(ids(search("몬 스테라"))).startsWith(inTitleAndSpecies.getId(), inSpeciesOnly.getId());
        // 설명에만 있는 상품은 품종명에 있는 상품보다 뒤
        assertThat(ids(search("몬스테라")))
                .containsExactly(inTitleAndSpecies.getId(), inSpeciesOnly.getId(), inDescriptionOnly.getId());
    }

    @Test
    @DisplayName("[완료 기준 2 · S-C02] 검수 중 · 반려 · 숨김 · 삭제 상품은 검색에 나오지 않음, onSale=true면 판매 중만")
    void exposureRule() throws IOException {
        Product active = save(MONSTERA_ID, SELLER_ID, "몬스테라 판매 중", "설명", ProductStatus.ACTIVE);
        Product pending = save(MONSTERA_ID, SELLER_ID, "몬스테라 결제 대기", "설명", ProductStatus.PAYMENT_PENDING);
        Product soldOut = save(MONSTERA_ID, SELLER_ID, "몬스테라 판매 완료", "설명", ProductStatus.SOLD_OUT);
        save(MONSTERA_ID, SELLER_ID, "몬스테라 검수 중", "설명", ProductStatus.REVIEW_PENDING);
        save(MONSTERA_ID, SELLER_ID, "몬스테라 반려", "설명", ProductStatus.REJECTED);
        Product hidden = save(MONSTERA_ID, SELLER_ID, "몬스테라 숨김", "설명", ProductStatus.ACTIVE);
        change(hidden, product -> product.changeHidden(true));
        Product deleted = save(MONSTERA_ID, SELLER_ID, "몬스테라 삭제", "설명", ProductStatus.ACTIVE);
        change(deleted, Product::delete);
        index();

        Page<ProductSearchResponse> all = search("몬스테라");
        assertThat(ids(all)).containsExactlyInAnyOrder(active.getId(), pending.getId(), soldOut.getId());
        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(ids(searchService.search(request("몬스테라", true, null, null, null))))
                .containsExactly(active.getId());
    }

    @Test
    @DisplayName("[완료 기준 3] OpenSearch를 내려도 같은 API가 DB LIKE 검색 결과로 응답 (재시도 1회 뒤 대체)")
    void fallsBackToLikeWhenOpenSearchIsDown() throws Exception {
        Product active = save(MONSTERA_ID, SELLER_ID, "몬스테라 판매 중", "설명", ProductStatus.ACTIVE);
        Product hidden = save(MONSTERA_ID, SELLER_ID, "몬스테라 숨김", "설명", ProductStatus.ACTIVE);
        change(hidden, product -> product.changeHidden(true));
        save(SANSEVIERIA_ID, SELLER_ID, "산세베리아", "설명", ProductStatus.ACTIVE);
        // 운영과 같은 짧은 읽기 타임아웃(2초)으로 붙는 검색 서비스
        ProductSearchService shortTimeout = new ProductSearchService(productRepository,
                new OpenSearchProductSearch(client(Duration.ofSeconds(2))), transactionManager);

        var docker = OPENSEARCH.getDockerClient();
        docker.pauseContainerCmd(OPENSEARCH.getContainerId()).exec();
        try {
            long started = System.currentTimeMillis();
            Page<ProductSearchResponse> page = shortTimeout.search(request("몬스테라", null, null, null, null));

            // 인덱스는 비어 있지만 DB LIKE로 찾음 (숨김 제외 규칙은 같음)
            assertThat(ids(page)).containsExactly(active.getId());
            // 2초 × 2번 시도 뒤 대체 (넉넉히 10초 안)
            assertThat(System.currentTimeMillis() - started).isLessThan(10_000);
        } finally {
            docker.unpauseContainerCmd(OPENSEARCH.getContainerId()).exec();
        }
    }

    @Test
    @DisplayName("[완료 기준 4] sellerId + status=SOLD_OUT으로 판매자 상점의 판매 완료 목록과 전체 개수 (키워드 있음 · 없음 모두)")
    void sellerShopSoldOut() throws IOException {
        Product sold1 = save(MONSTERA_ID, SELLER_ID, "몬스테라 1", "설명", ProductStatus.SOLD_OUT);
        Product sold2 = save(SCINDAPSUS_ID, SELLER_ID, "스킨답서스 2", "설명", ProductStatus.SOLD_OUT);
        save(MONSTERA_ID, SELLER_ID, "몬스테라 판매 중", "설명", ProductStatus.ACTIVE);
        save(MONSTERA_ID, OTHER_SELLER_ID, "남의 몬스테라", "설명", ProductStatus.SOLD_OUT);
        index();

        // 키워드 없음 → DB 조회
        Page<ProductSearchResponse> shop = searchService.search(request(null, null, SELLER_ID, ProductStatus.SOLD_OUT, null));
        assertThat(ids(shop)).containsExactlyInAnyOrder(sold1.getId(), sold2.getId());
        assertThat(shop.getTotalElements()).isEqualTo(2);
        assertThat(shop.getContent()).extracting(ProductSearchResponse::sellerId).containsOnly(SELLER_ID);

        // 키워드 있음 → OpenSearch (문서에 sellerId 색인)
        Page<ProductSearchResponse> keyword = searchService.search(
                request("몬스테라", null, SELLER_ID, ProductStatus.SOLD_OUT, null));
        assertThat(ids(keyword)).containsExactly(sold1.getId());
        // 화면 값(썸네일)은 DB에서 다시 읽음 (지연 로딩도 서비스 트랜잭션 안에서)
        assertThat(keyword.getContent().getFirst().thumbnailUrl()).endsWith("/a.jpg");
        assertThat(keyword.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("필터(품종 · 가격)와 가격 정렬은 OpenSearch에서도 DB 조회와 같게")
    void filtersAndPriceSort() throws IOException {
        Product cheap = save(MONSTERA_ID, SELLER_ID, "몬스테라 소형", "설명", ProductStatus.ACTIVE, "10000");
        Product middle = save(MONSTERA_ID, SELLER_ID, "몬스테라 중형", "설명", ProductStatus.ACTIVE, "30000");
        save(MONSTERA_ID, SELLER_ID, "몬스테라 대형", "설명", ProductStatus.ACTIVE, "90000");
        save(SCINDAPSUS_ID, SELLER_ID, "몬스테라 닮은 스킨답서스", "설명", ProductStatus.ACTIVE, "20000");
        index();

        ProductSearchRequest request = new ProductSearchRequest("몬스테라", MONSTERA_ID, null, null, null, null,
                null, new BigDecimal("50000"), null, null, null, "priceDesc", null, null);
        assertThat(ids(searchService.search(request))).containsExactly(middle.getId(), cheap.getId());
    }

    private Product save(Long speciesId, Long sellerId, String title, String description, ProductStatus status) {
        return save(speciesId, sellerId, title, description, status, "25000");
    }

    private Product save(Long speciesId, Long sellerId, String title, String description, ProductStatus status,
                         String price) {
        Product product = Product.create(speciesRepository.findById(speciesId).orElseThrow(), sellerId, title,
                description, new BigDecimal(price), null, null, null,
                List.of("http://localhost:9000/bucket/products/" + sellerId + "/a.jpg"));
        ReflectionTestUtils.setField(product, "status", status);
        return productRepository.save(product);
    }

    // 저장된 상품을 다시 읽어 바꾸고 커밋 (숨김 · 삭제)
    private void change(Product product, Consumer<Product> change) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                change.accept(productRepository.findById(product.getId()).orElseThrow()));
    }

    // 전체 색인으로 DB 상품을 문서로 채우고, 바로 검색되게 refresh
    private void index() throws IOException {
        assertThat(indexService.reindexAll().failed()).isZero();
        client.indices().refresh(request -> request.index(ProductIndexInitializer.ALIAS));
    }

    private Page<ProductSearchResponse> search(String keyword) {
        return searchService.search(request(keyword, null, null, null, null));
    }

    private static ProductSearchRequest request(String keyword, Boolean onSale, Long sellerId, ProductStatus status,
                                                String sort) {
        return new ProductSearchRequest(keyword, null, null, null, null, null, null, null, onSale, sellerId, status,
                sort, null, null);
    }

    private static List<Long> ids(Page<ProductSearchResponse> page) {
        return page.getContent().stream().map(ProductSearchResponse::productId).toList();
    }
}
