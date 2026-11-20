package com.justcommit.backend.product.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.common.messaging.consumer.MessagingConsumerAutoConfiguration;
import com.justcommit.backend.common.messaging.outbox.OutboxAutoConfiguration;
import com.justcommit.backend.common.messaging.outbox.OutboxEventRepository;
import com.justcommit.backend.common.testcontainers.SharedKafka;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.config.ClockConfig;
import com.justcommit.backend.product.product.application.ProductEventPublisher;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.search.application.ProductDocument;
import com.justcommit.backend.product.search.application.ProductIndexService;
import com.justcommit.backend.product.search.application.ProductIndexService.ReindexResult;
import com.justcommit.backend.product.search.application.ProductSearchIndex;
import com.justcommit.backend.product.search.application.ProductSearchService;
import com.justcommit.backend.product.product.presentation.dto.ProductSearchRequest;
import com.justcommit.backend.product.product.presentation.dto.ProductSearchResponse;
import com.justcommit.backend.product.search.infrastructure.OpenSearchConfig;
import com.justcommit.backend.product.search.infrastructure.OpenSearchProductIndex;
import com.justcommit.backend.product.search.infrastructure.OpenSearchProductSearch;
import com.justcommit.backend.product.search.infrastructure.ProductIndexAdmin;
import com.justcommit.backend.product.search.infrastructure.ProductIndexInitializer;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.GetResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * 상품 검색 색인 (#485): 상품 변경 → Outbox → Kafka(product.events.v1) → 색인 리스너 → DB 재조회 → OpenSearch 문서.
 * PostgreSQL · Kafka · OpenSearch(nori 이미지) 모두 실제 컨테이너. 외부 API는 쓰지 않음.
 */
@DataJpaTest(properties = "outbox.scheduler.enabled=false")   // 커밋 직후 발행만으로 흐름이 이어지는지 확인
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, ProductEventPublisher.class, ProductIndexService.class,
        OpenSearchProductIndex.class, ProductIndexAdmin.class, ProductSearchIndexListener.class, OpenSearchProductSearch.class,
        ProductSearchService.class, ProductSearchIndexScenarioTest.Config.class})
@ImportAutoConfiguration({KafkaAutoConfiguration.class, MessagingConsumerAutoConfiguration.class,
        OutboxAutoConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
// 상품 변경 트랜잭션이 실제로 커밋돼야 Outbox가 발행됨 (테스트를 트랜잭션으로 감싸지 않음)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
// 리스너가 도는 클래스: 끝나면 컨텍스트를 닫아 다른 클래스의 메시지를 읽지 않게 (docs/testing/strategy.md)
@DirtiesContext
class ProductSearchIndexScenarioTest {

    private static final Long SELLER_ID = 4850L;
    // V4 시드 품종 「몬스테라」 (학명 Monstera deliciosa)
    private static final Long MONSTERA_ID = 62L;
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
        SharedKafka.register(registry);
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

        // 운영 설정과 같은 클래스로 만들되 주소만 컨테이너로, 읽기 타임아웃은 느린 CI를 위해 넉넉히
        @Bean
        OpenSearchClient openSearchClient() throws URISyntaxException {
            OpenSearchConfig config = new OpenSearchConfig(
                    "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200),
                    Duration.ofSeconds(1), Duration.ofSeconds(10));
            return config.openSearchClient(config.openSearchTransport());
        }
    }

    @Autowired OpenSearchClient client;
    @Autowired ProductIndexService productIndexService;
    @Autowired ProductSearchIndex productSearchIndex;
    @Autowired ProductSearchService productSearchService;
    @Autowired ProductEventPublisher productEventPublisher;
    @Autowired ProductRepository productRepository;
    @Autowired SpeciesRepository speciesRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    // 테스트마다 빈 인덱스로 시작 (앱이 뜰 때와 같은 초기화 코드로 alias products → products_v1)
    @BeforeEach
    void recreateIndex() throws IOException {
        if (client.indices().exists(request -> request.index(INDEX_V1)).value()) {
            client.indices().delete(request -> request.index(INDEX_V1));
        }
        new ProductIndexInitializer(new ProductIndexAdmin(client)).initialize();
        assertThat(client.indices().existsAlias(request -> request.name(ProductIndexInitializer.ALIAS)).value()).isTrue();
    }

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAllInBatch();
        jdbc.update("delete from product_image");
        productRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("[S-C01] 등록 → 검수 통과 → 숨김 → 삭제 이벤트마다 리스너가 DB를 다시 읽어 문서를 맞춘다")
    void documentFollowsProductLifecycle() throws Exception {
        // 1) 등록: 검수 중 상품도 문서는 만든다 (검색할 때 상태로 거름, #486)
        Long productId = tx.execute(status -> {
            Product saved = productRepository.save(newProduct("몬스테라 알보 팝니다"));
            productEventPublisher.registered(saved.getId());
            return saved.getId();
        });
        ProductDocument registered = waitForDocument(productId, doc -> "REVIEW_PENDING".equals(doc.status()));
        assertThat(registered.title()).isEqualTo("몬스테라 알보 팝니다");
        assertThat(registered.speciesName()).isEqualTo("몬스테라");
        assertThat(registered.scientificName()).isEqualTo("Monstera deliciosa");
        assertThat(registered.speciesAliases()).containsExactlyInAnyOrderElementsOf(aliasesOf(MONSTERA_ID));
        assertThat(registered.price()).isEqualTo(25_000L);
        assertThat(registered.hidden()).isFalse();

        // 2) 검수 통과: ACTIVE. 검색어로도 찾힌다 (nori 분석기가 적용된 제목)
        changeAndPublish(productId, product -> ReflectionTestUtils.setField(product, "status", ProductStatus.ACTIVE));
        waitForDocument(productId, doc -> "ACTIVE".equals(doc.status()));
        client.indices().refresh(request -> request.index(ProductIndexInitializer.ALIAS));
        var hits = client.search(request -> request.index(ProductIndexInitializer.ALIAS)
                .query(query -> query.match(match -> match.field("title").query(value -> value.stringValue("몬스테라")))),
                ProductDocument.class).hits().hits();
        assertThat(hits).extracting(hit -> hit.id()).containsExactly(String.valueOf(productId));

        // 3) 숨김: 문서는 남고 hidden = true (검색할 때 거름)
        changeAndPublish(productId, product -> product.changeHidden(true));
        ProductDocument hidden = waitForDocument(productId, ProductDocument::hidden);
        assertThat(hidden.status()).isEqualTo("ACTIVE");

        // 4) 삭제: 문서가 사라진다
        changeAndPublish(productId, Product::delete);
        waitUntilNoDocument(productId);
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 받아도 문서 · 버전이 같다")
    void duplicateEventKeepsSameDocument() throws Exception {
        Long productId = tx.execute(status -> productRepository.save(newProduct("고무나무")).getId());

        productIndexService.sync(productId);
        GetResponse<ProductDocument> first = get(productId);
        productIndexService.sync(productId);
        GetResponse<ProductDocument> second = get(productId);

        assertThat(second.source()).isEqualTo(first.source());
        // 외부 버전 = 상품 버전. 두 번째 쓰기는 같은 버전이라 건너뜀
        long productVersion = productRepository.findById(productId).orElseThrow().getVersion();
        assertThat(second.version()).isEqualTo(productVersion).isEqualTo(first.version());
    }

    @Test
    @DisplayName("옛 버전 쓰기는 더 새 문서를 덮지 않는다 (전체 색인과 이벤트 색인이 겹칠 때)")
    void olderVersionDoesNotOverwrite() throws Exception {
        Long productId = tx.execute(status -> productRepository.save(newProduct("새 제목")).getId());
        Product product = productRepository.findWithSpeciesById(productId).orElseThrow();
        ProductDocument newer = ProductDocument.of(product, List.of());
        ProductDocument older = new ProductDocument(productId, "옛 제목", newer.description(), newer.speciesId(),
                newer.speciesName(), List.of(), newer.scientificName(), newer.category(), newer.light(),
                newer.watering(), newer.difficulty(), newer.price(), "REVIEW_PENDING", false, newer.createdAt());

        productSearchIndex.save(newer, 5);
        productSearchIndex.save(older, 3);

        assertThat(get(productId).source().title()).isEqualTo("새 제목");
        assertThat(get(productId).version()).isEqualTo(5);
    }

    @Test
    @DisplayName("전체 색인: 삭제 상품 문서는 지우고 나머지는 덮어쓰며, 다시 돌려도 결과가 같다")
    void reindexAll() throws Exception {
        // 이벤트 없이 DB에만 있는 상품 (처음 띄울 때처럼)
        Long activeId = tx.execute(status -> {
            Product product = productRepository.save(newProduct("판매 중 상품"));
            ReflectionTestUtils.setField(product, "status", ProductStatus.ACTIVE);
            return product.getId();
        });
        Long deletedId = tx.execute(status -> productRepository.save(newProduct("삭제할 상품")).getId());
        tx.executeWithoutResult(status -> productRepository.findById(deletedId).orElseThrow().delete());
        // 삭제 전에 남아 있던 옛 문서 (버전 0 < 삭제 후 버전 1)
        Product deleted = productRepository.findWithSpeciesById(deletedId).orElseThrow();
        productSearchIndex.save(ProductDocument.of(deleted, List.of()), 0);

        ReindexResult first = productIndexService.reindexAll();

        assertThat(first.indexed()).isEqualTo(1);
        assertThat(first.deleted()).isEqualTo(1);
        assertThat(first.failed()).isZero();
        assertThat(get(activeId).source().status()).isEqualTo("ACTIVE");
        assertThat(get(deletedId).found()).isFalse();

        // 두 번째: 모두 같은 버전이라 건너뜀
        ReindexResult second = productIndexService.reindexAll();
        assertThat(second.skipped()).isEqualTo(2);
        assertThat(second.failed()).isZero();
        assertThat(get(activeId).source().status()).isEqualTo("ACTIVE");
        assertThat(get(deletedId).found()).isFalse();
    }

    @Test
    @DisplayName("OpenSearch가 멈춰도 상품 저장은 커밋되고, 다시 살아나면 전체 색인으로 문서를 채운다")
    void productSaveSucceedsWhileOpenSearchIsDown() throws Exception {
        var docker = OPENSEARCH.getDockerClient();
        docker.pauseContainerCmd(OPENSEARCH.getContainerId()).exec();
        Long productId;
        try {
            // 등록 API와 같은 트랜잭션 모양: 상품 저장 + Outbox. OpenSearch를 부르지 않으므로 커밋된다
            productId = tx.execute(status -> {
                Product saved = productRepository.save(newProduct("장애 중 등록"));
                productEventPublisher.registered(saved.getId());
                return saved.getId();
            });
            assertThat(productRepository.findById(productId)).isPresent();
            assertThat(outboxEventRepository.findAll()).extracting(row -> row.getEventType())
                    .contains("ProductRegistered");
        } finally {
            docker.unpauseContainerCmd(OPENSEARCH.getContainerId()).exec();
        }

        // 리스너가 재시도 중 성공하든 DLT로 빠지든, 전체 색인을 돌리면 문서가 맞춰짐
        productIndexService.reindexAll();
        assertThat(waitForDocument(productId, doc -> "REVIEW_PENDING".equals(doc.status())).title())
                .isEqualTo("장애 중 등록");
    }

    @Test
    @DisplayName("[S-C02] 숨긴 상품은 이벤트로 문서가 hidden이 되어 OpenSearch 검색에서 빠지고, 숨김을 풀면 다시 나온다")
    void hiddenProductIsExcludedFromSearch() throws Exception {
        Long productId = tx.execute(status -> {
            Product saved = productRepository.save(newProduct("몬스테라 숨김 시험"));
            ReflectionTestUtils.setField(saved, "status", ProductStatus.ACTIVE);
            productEventPublisher.registered(saved.getId());
            return saved.getId();
        });
        waitForDocument(productId, doc -> "ACTIVE".equals(doc.status()));
        assertThat(searchIds("몬스테라")).containsExactly(productId);

        // 숨김 → ProductStatusChanged → 리스너가 문서를 hidden = true로
        changeAndPublish(productId, product -> product.changeHidden(true));
        waitForDocument(productId, ProductDocument::hidden);
        assertThat(searchIds("몬스테라")).isEmpty();

        // 숨김 해제 → 다시 검색됨 (문서를 새로 만들지 않고 같은 문서를 덮어씀)
        changeAndPublish(productId, product -> product.changeHidden(false));
        waitForDocument(productId, doc -> !doc.hidden());
        assertThat(searchIds("몬스테라")).containsExactly(productId);
    }

    // 검색 API와 같은 서비스로 (색인 직후 바로 보이게 refresh)
    private List<Long> searchIds(String keyword) throws IOException {
        client.indices().refresh(request -> request.index(ProductIndexInitializer.ALIAS));
        return productSearchService.search(new ProductSearchRequest(keyword, null, null, null, null, null, null, null,
                        null, null, null, null, null, null))
                .getContent().stream().map(ProductSearchResponse::productId).toList();
    }

    private Product newProduct(String title) {
        Species monstera = speciesRepository.findById(MONSTERA_ID).orElseThrow();
        return Product.create(monstera, SELLER_ID, title, "잘 키웠어요", new BigDecimal("25000"), null, null, null,
                List.of("http://localhost:9000/bucket/products/4850/a.jpg"));
    }

    // 업무 코드처럼: 상품을 바꾸고 같은 트랜잭션에서 ProductStatusChanged를 Outbox에 (커밋 직후 Kafka로)
    private void changeAndPublish(Long productId, java.util.function.Consumer<Product> change) {
        tx.executeWithoutResult(status -> {
            change.accept(productRepository.findById(productId).orElseThrow());
            productEventPublisher.statusChanged(productId);
        });
    }

    private List<String> aliasesOf(Long speciesId) {
        return jdbc.queryForList("select alias from species_alias where species_id = ?", String.class, speciesId);
    }

    private GetResponse<ProductDocument> get(Long productId) throws IOException {
        return client.get(request -> request.index(ProductIndexInitializer.ALIAS).id(String.valueOf(productId)),
                ProductDocument.class);
    }

    // 리스너가 반영할 때까지 기다림 (컨슈머 그룹 참여 · 토픽 생성 시간 포함). GET은 refresh 없이 바로 최신 문서를 읽음
    private ProductDocument waitForDocument(Long productId, Predicate<ProductDocument> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            GetResponse<ProductDocument> response = get(productId);
            if (response.found() && condition.test(response.source())) {
                return response.source();
            }
            Thread.sleep(300);
        }
        throw new AssertionError("60초 안에 색인 리스너가 문서를 맞추지 않음: productId=" + productId);
    }

    private void waitUntilNoDocument(Long productId) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (!get(productId).found()) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("60초 안에 삭제된 상품의 문서가 지워지지 않음: productId=" + productId);
    }
}
