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
import com.justcommit.backend.product.search.application.ProductIndexService.RebuildResult;
import com.justcommit.backend.product.search.application.ProductKeywordSearch;
import com.justcommit.backend.product.search.application.ProductSearchMetrics;
import com.justcommit.backend.product.search.application.ProductSearchMetrics.Stage;
import com.justcommit.backend.product.search.application.ProductSearchSort;
import com.justcommit.backend.product.search.application.ProductSearchService;
import com.justcommit.backend.product.search.infrastructure.OpenSearchConfig;
import com.justcommit.backend.product.search.infrastructure.OpenSearchProductIndex;
import com.justcommit.backend.product.search.infrastructure.OpenSearchProductSearch;
import com.justcommit.backend.product.search.infrastructure.ProductIndexAdmin;
import com.justcommit.backend.product.search.infrastructure.ProductIndexInitializer;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 상품 목록 검색 (#486 · #487): DB에 상품을 넣고 전체 색인으로 OpenSearch에 채운 뒤 {@link ProductSearchService}로 찾는다.
 * PostgreSQL · OpenSearch(nori 이미지) 모두 실제 컨테이너. 이슈 #486 · #487의 완료 기준을 테스트 하나씩으로 확인한다.
 *
 * <p>테스트를 트랜잭션으로 감싸지 않는다: 운영처럼 서비스가 스스로 연 트랜잭션에서 읽어야
 * (open-in-view 꺼짐) 썸네일 지연 로딩 같은 문제가 테스트에서도 드러남. 상품은 @AfterEach에서 지우고, 인덱스는 @BeforeEach에서 새로 만든다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, ProductIndexService.class, OpenSearchProductIndex.class,
        ProductIndexAdmin.class,
        OpenSearchProductSearch.class, ProductSearchService.class, ProductSearchMetrics.class,
        ProductSearchIntegrationTest.Config.class})
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
    private static final String INDEX_V2 = "products_v2";
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

        // JPA 슬라이스라 지표 자동 설정이 없어 단계별 시간(#634)을 받을 레지스트리를 직접 둔다
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private static OpenSearchClient client(Duration readTimeout) throws URISyntaxException {
        OpenSearchConfig config = new OpenSearchConfig(
                "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200),
                Duration.ofSeconds(1), readTimeout);
        return config.openSearchClient(config.openSearchTransport());
    }

    private static OpenSearchClient client(Duration readTimeout, int maxConnections, Duration connectionRequestTimeout)
            throws URISyntaxException {
        OpenSearchConfig config = new OpenSearchConfig(
                "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200),
                Duration.ofSeconds(1), readTimeout, maxConnections, connectionRequestTimeout);
        return config.openSearchClient(config.openSearchTransport());
    }

    @Autowired OpenSearchClient client;
    @Autowired ProductSearchService searchService;
    @Autowired ProductIndexService indexService;
    @Autowired ProductRepository productRepository;
    @Autowired SpeciesRepository speciesRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MeterRegistry meterRegistry;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from product_image");
        productRepository.deleteAllInBatch();
    }

    @BeforeEach
    void recreateIndex() throws IOException {
        // 재색인 테스트가 products_v2를 남기므로 번호와 상관없이 모두 지움
        client.indices().delete(request -> request.index("products_v*"));
        new ProductIndexInitializer(new ProductIndexAdmin(client)).initialize();
    }

    @Test
    @DisplayName("[#486 완료 기준 1 · #487 완료 기준 2] \"몬 스테라\"로 찾으면 관련도순 (제목 > 품종명 · 별칭 · 학명 > 설명)")
    void spacedKeywordFindsByRelevance() throws IOException {
        Product inTitleAndSpecies = save(MONSTERA_ID, SELLER_ID, "몬스테라 알보 팝니다", "잘 키웠어요", ProductStatus.ACTIVE);
        Product inSpeciesOnly = save(MONSTERA_ID, SELLER_ID, "거실 대형 화분", "잘 키웠어요", ProductStatus.ACTIVE);
        Product scindapsus = save(SCINDAPSUS_ID, SELLER_ID, "덩굴 식물", "몬스테라 옆에서 키웠어요", ProductStatus.ACTIVE);
        save(SANSEVIERIA_ID, SELLER_ID, "산세베리아 분양", "잘 키웠어요", ProductStatus.ACTIVE);
        index();

        // 띄어쓰기를 무시해 찾고, 제목 · 품종명 둘 다 맞는 상품이 맨 앞
        assertThat(ids(search("몬 스테라"))).startsWith(inTitleAndSpecies.getId(), inSpeciesOnly.getId());
        // 설명에만 있는 상품도 나오지만 제목 · 품종명에 있는 상품보다 뒤 (#487)
        assertThat(ids(search("몬스테라")))
                .containsExactly(inTitleAndSpecies.getId(), inSpeciesOnly.getId(), scindapsus.getId());
        assertThat(ids(search("옆에서"))).containsExactly(scindapsus.getId());
        // 별칭 (V4 시드: 스킨답서스 → 신답서스) · 학명(대소문자 무시)으로도 찾음
        assertThat(ids(search("신답서스"))).containsExactly(scindapsus.getId());
        assertThat(ids(search("monstera Deliciosa")))
                .containsExactlyInAnyOrder(inTitleAndSpecies.getId(), inSpeciesOnly.getId());
    }

    @Test
    @DisplayName("[#634] 키워드 검색은 전체 · OpenSearch 호출 · took · DB 재조회 시간을 단계별 Timer로 남긴다")
    void recordsStageTimers() throws IOException {
        save(MONSTERA_ID, SELLER_ID, "몬스테라 알보 팝니다", "잘 키웠어요", ProductStatus.ACTIVE);
        index();
        // 레지스트리는 이 클래스의 다른 테스트와 같이 쓰므로 늘어난 만큼만 본다
        Map<Stage, Long> before = new EnumMap<>(Stage.class);
        for (Stage stage : Stage.values()) {
            before.put(stage, count(stage));
        }

        search("몬스테라");

        for (Stage stage : List.of(Stage.TOTAL, Stage.OPENSEARCH, Stage.OPENSEARCH_TOOK, Stage.DB)) {
            assertThat(count(stage)).as(stage.tag()).isEqualTo(before.get(stage) + 1);
        }
        // OpenSearch가 응답했으므로 LIKE 대체는 없음
        assertThat(count(Stage.FALLBACK)).isEqualTo(before.get(Stage.FALLBACK));
    }

    private long count(Stage stage) {
        return timer(stage).count();
    }

    private Timer timer(Stage stage) {
        return meterRegistry.get(ProductSearchMetrics.NAME).tag("stage", stage.tag())
                .tag("outcome", ProductSearchMetrics.SUCCESS).timer();
    }

    @Test
    @DisplayName("[#486 완료 기준 2 · S-C02] 검수 중 · 반려 · 숨김 · 삭제 상품은 검색에 나오지 않음, onSale=true면 판매 중만")
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
    @DisplayName("[#486 완료 기준 3] OpenSearch가 응답 없이 멈춰도 같은 API가 DB LIKE 검색 결과로 응답 (읽기 시간 초과는 재시도 없이 대체, #574)")
    void fallsBackToLikeWhenOpenSearchIsDown() throws Exception {
        Product active = save(MONSTERA_ID, SELLER_ID, "몬스테라 판매 중", "설명", ProductStatus.ACTIVE);
        Product hidden = save(MONSTERA_ID, SELLER_ID, "몬스테라 숨김", "설명", ProductStatus.ACTIVE);
        change(hidden, product -> product.changeHidden(true));
        save(SANSEVIERIA_ID, SELLER_ID, "산세베리아", "설명", ProductStatus.ACTIVE);
        // 운영과 같은 짧은 읽기 타임아웃(2초)으로 붙는 검색 서비스
        ProductSearchService shortTimeout = new ProductSearchService(productRepository,
                new OpenSearchProductSearch(client(Duration.ofSeconds(2))), transactionManager,
                new ProductSearchMetrics(new SimpleMeterRegistry()));

        var docker = OPENSEARCH.getDockerClient();
        docker.pauseContainerCmd(OPENSEARCH.getContainerId()).exec();
        try {
            long started = System.currentTimeMillis();
            Page<ProductSearchResponse> page = shortTimeout.search(request("몬스테라", null, null, null, null));

            // 인덱스는 비어 있지만 DB LIKE로 찾음 (숨김 제외 규칙은 같음)
            assertThat(ids(page)).containsExactly(active.getId());
            // 읽기 2초 1번 뒤 대체. 예전처럼 2번 기다리면(4초 이상) 실패 (느린 CI를 위해 3.5초)
            assertThat(System.currentTimeMillis() - started).isLessThan(3_500);
        } finally {
            docker.unpauseContainerCmd(OPENSEARCH.getContainerId()).exec();
        }
    }

    @Test
    @DisplayName("[#574] 응답 없는 OpenSearch에 동시에 몰리면 연결 풀 밖의 요청은 줄 서지 않고 빌리기 대기(0.2초) 뒤 바로 LIKE로")
    void fallsBackQuicklyWhenConnectionPoolIsExhausted() throws Exception {
        Product active = save(MONSTERA_ID, SELLER_ID, "몬스테라 판매 중", "설명", ProductStatus.ACTIVE);
        // 연결 풀 · 동시 검색 상한 2개 · 빌리기 대기 0.2초 · 읽기 2초 (운영은 20개)
        ProductSearchService smallPool = new ProductSearchService(productRepository,
                new OpenSearchProductSearch(client(Duration.ofSeconds(2), 2, Duration.ofMillis(200)), 2, Duration.ofMillis(200)),
                transactionManager, new ProductSearchMetrics(new SimpleMeterRegistry()));

        var docker = OPENSEARCH.getDockerClient();
        docker.pauseContainerCmd(OPENSEARCH.getContainerId()).exec();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Long>> elapsed = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                elapsed.add(pool.submit(() -> {
                    long started = System.currentTimeMillis();
                    Page<ProductSearchResponse> page = smallPool.search(request("몬스테라", null, null, null, null));
                    assertThat(ids(page)).containsExactly(active.getId());
                    return System.currentTimeMillis() - started;
                }));
            }
            List<Long> millis = new ArrayList<>();
            for (Future<Long> future : elapsed) {
                millis.add(future.get());
            }
            // 8개 모두 LIKE로 응답 · 풀을 얻은 2개만 읽기 2초를 기다리고 나머지 6개는 빠르게 (예전엔 줄 서서 2초씩 차례로)
            assertThat(millis).allSatisfy(ms -> assertThat(ms).isLessThan(3_500));
            assertThat(millis.stream().filter(ms -> ms < 1_500).count()).isGreaterThanOrEqualTo(6);
        } finally {
            pool.shutdownNow();
            docker.unpauseContainerCmd(OPENSEARCH.getContainerId()).exec();
        }
    }

    @Test
    @DisplayName("[#486 완료 기준 4] sellerId + status=SOLD_OUT으로 판매자 상점의 판매 완료 목록과 전체 개수 (판매자 상점은 키워드가 있어도 DB)")
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

        // 키워드가 있어도 DB(LIKE) — OpenSearch 문서에는 판매자를 색인하지 않음
        Page<ProductSearchResponse> keyword = searchService.search(
                request("몬스테라", null, SELLER_ID, ProductStatus.SOLD_OUT, null));
        assertThat(ids(keyword)).containsExactly(sold1.getId());
        // 썸네일 지연 로딩도 서비스의 읽기 전용 트랜잭션 안에서
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

    @Test
    @DisplayName("OpenSearch 결과 창(10,000건)을 넘는 깊은 페이지는 LIKE로 넘어가지 않고 빈 페이지 · 전체 개수")
    void pageBeyondResultWindow() throws IOException {
        save(MONSTERA_ID, SELLER_ID, "몬스테라 소형", "설명", ProductStatus.ACTIVE);
        save(MONSTERA_ID, SELLER_ID, "몬스테라 중형", "설명", ProductStatus.ACTIVE);
        index();

        ProductSearchRequest deep = new ProductSearchRequest("몬스테라", null, null, null, null, null,
                null, null, null, null, null, null, 600, 20);
        Page<ProductSearchResponse> page = searchService.search(deep);
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("[#487 완료 기준 1] 오타 허용: \"몬스테러\"로도 몬스테라 상품이 나옴 (3 ~ 5글자는 1글자, 1 ~ 2글자는 정확히)")
    void typoTolerance() throws IOException {
        Product monstera = save(MONSTERA_ID, SELLER_ID, "몬스테라 알보 팝니다", "잘 키웠어요", ProductStatus.ACTIVE);
        Product monsteraBySpecies = save(MONSTERA_ID, SELLER_ID, "거실 대형 화분", "잘 키웠어요", ProductStatus.ACTIVE);
        Product scindapsus = save(SCINDAPSUS_ID, SELLER_ID, "덩굴 식물", "잘 키웠어요", ProductStatus.ACTIVE);
        save(SANSEVIERIA_ID, SELLER_ID, "산세베리아 분양", "잘 키웠어요", ProductStatus.ACTIVE);
        index();

        // 4글자 중 1글자 틀림 → 제목 · 품종명에서 찾음 (한국어 분석기는 몬스 + 테러로 잘라 공백 단위 .word 필드로 찾음)
        assertThat(ids(search("몬스테러"))).containsExactlyInAnyOrder(monstera.getId(), monsteraBySpecies.getId());
        // 5글자 중 1글자 틀림
        assertThat(ids(search("스킨탑서스"))).containsExactly(scindapsus.getId());
        // 여러 단어도 단어마다 오타 허용, 모두 맞아야 함
        assertThat(ids(search("몬스테러 알보"))).containsExactly(monstera.getId());
        // 정확히 맞는 상품이 오타로 맞는 상품보다 앞 (오타 허용은 가중치 절반)
        Product typoTitle = save(SANSEVIERIA_ID, SELLER_ID, "몬스테러 같은 산세베리아", "잘 키웠어요", ProductStatus.ACTIVE);
        index();
        assertThat(ids(search("몬스테라"))).endsWith(typoTitle.getId());
        // 5글자 중 2글자 틀림은 허용하지 않음
        assertThat(ids(search("산세베이라"))).isEmpty();
    }

    @Test
    @DisplayName("[#487 완료 기준 3] 무중단 재색인: products_v2를 채우고 alias를 옮기는 동안에도 검색이 계속 응답")
    void rebuildWithoutDowntime() throws Exception {
        Product target = save(MONSTERA_ID, SELLER_ID, "몬스테라 재색인 확인", "설명", ProductStatus.ACTIVE);
        // 배치(500개) 여러 번에 걸치도록 상품을 더 넣어 재색인이 잠깐이라도 이어지게
        Species sansevieria = speciesRepository.findById(SANSEVIERIA_ID).orElseThrow();
        List<Product> others = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            others.add(Product.create(sansevieria, SELLER_ID, "산세베리아 " + i, "설명", new BigDecimal("10000"),
                    null, null, null, List.of("http://localhost:9000/bucket/products/" + SELLER_ID + "/a.jpg")));
        }
        others.forEach(product -> ReflectionTestUtils.setField(product, "status", ProductStatus.ACTIVE));
        productRepository.saveAll(others);
        index();

        // LIKE 대체가 없는 OpenSearch 검색을 직접 계속 불러, 한 번이라도 실패하거나 못 찾으면 기록
        OpenSearchProductSearch direct = new OpenSearchProductSearch(client);
        ProductKeywordSearch.Condition condition = new ProductKeywordSearch.Condition("몬스테라 재색인",
                List.of(ProductStatus.ACTIVE, ProductStatus.PAYMENT_PENDING, ProductStatus.SOLD_OUT),
                null, null, null, null, null, null, null);
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicInteger searches = new AtomicInteger();
        List<String> problems = new CopyOnWriteArrayList<>();
        Thread searcher = new Thread(() -> {
            while (!done.get()) {
                try {
                    if (!direct.search(condition, ProductSearchSort.RELEVANCE, 0, 20).productIds().contains(target.getId())) {
                        problems.add("못 찾음");
                    }
                    searches.incrementAndGet();
                } catch (RuntimeException e) {
                    problems.add(e.toString());
                }
            }
        });
        searcher.start();
        RebuildResult result;
        try {
            result = indexService.rebuild();
        } finally {
            done.set(true);
            searcher.join();
        }

        assertThat(result.previousIndex()).isEqualTo(INDEX_V1);
        assertThat(result.currentIndex()).isEqualTo(INDEX_V2);
        assertThat(result.filled().indexed()).isEqualTo(1201);
        assertThat(result.filled().failed()).isZero();
        // alias를 옮긴 뒤 따라잡기 색인은 그사이 바뀐 상품이 없어 모두 같은 버전이라 건너뜀
        assertThat(result.caughtUp().skipped()).isEqualTo(1201);
        assertThat(result.previousDeleted()).isTrue();
        assertThat(searches.get()).isPositive();
        assertThat(problems).isEmpty();
        // alias는 products_v2 하나만 가리키고, products_v1은 지워짐
        assertThat(client.indices().getAlias(request -> request.name(ProductIndexInitializer.ALIAS)).result())
                .containsOnlyKeys(INDEX_V2);
        assertThat(client.indices().exists(request -> request.index(INDEX_V1)).value()).isFalse();
        // 재색인 뒤에도 이벤트 · 전체 색인 쓰기와 검색이 그대로 (alias로 읽고 씀)
        assertThat(ids(search("몬스테라 재색인"))).containsExactly(target.getId());
        assertThat(indexService.rebuild().currentIndex()).isEqualTo("products_v3");
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
        return new ProductSearchRequest(keyword, null, null, null, null, null, null, null, onSale, sellerId,
                status == null ? null : List.of(status), sort, null, null);
    }

    private static List<Long> ids(Page<ProductSearchResponse> page) {
        return page.getContent().stream().map(ProductSearchResponse::productId).toList();
    }
}
