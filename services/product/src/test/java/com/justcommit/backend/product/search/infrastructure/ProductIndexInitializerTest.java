package com.justcommit.backend.product.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.indices.analyze.AnalyzeToken;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 상품 검색 인덱스 생성 · 분석기 테스트 (#484).
 *
 * <p>로컬 compose · 운영 데이터 서버와 같은 docker/opensearch/Dockerfile(공식 이미지 + nori)로 이미지를 만든다.
 * 스프링 컨텍스트 없이 설정 클래스로 클라이언트만 만들어 OpenSearch만 띄운다.
 */
@Testcontainers
class ProductIndexInitializerTest {

    // Gradle은 모듈 폴더(services/product)에서 테스트를 돌린다
    private static final Path DOCKERFILE_DIR = Path.of("../../docker/opensearch").toAbsolutePath().normalize();

    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            new ImageFromDockerfile("replant-opensearch-test", false).withFileFromPath(".", DOCKERFILE_DIR))
            .withEnv("discovery.type", "single-node")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static OpenSearchClient client;
    private ProductIndexInitializer initializer;

    @BeforeAll
    static void createClient() throws Exception {
        String url = "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200);
        OpenSearchConfig config = new OpenSearchConfig(url, Duration.ofSeconds(1), Duration.ofSeconds(10));
        client = config.openSearchClient(config.openSearchTransport());
    }

    @BeforeEach
    void deleteIndex() throws Exception {
        if (client.indices().exists(request -> request.index(ProductIndexInitializer.INDEX_V1)).value()) {
            client.indices().delete(request -> request.index(ProductIndexInitializer.INDEX_V1));
        }
        initializer = new ProductIndexInitializer(new ProductIndexAdmin(client));
    }

    @Test
    @DisplayName("alias가 없으면 products_v1을 만들고 alias products를 붙인다")
    void createsIndexWithAlias() throws Exception {
        initializer.createIndexIfAbsent();

        var aliases = client.indices().getAlias(request -> request.name(ProductIndexInitializer.ALIAS)).result();
        assertThat(aliases).containsOnlyKeys(ProductIndexInitializer.INDEX_V1);
    }

    @Test
    @DisplayName("매핑: 검색 필드는 ko_text + .compact(ko_compact) + .word(ko_word), 필터 · 정렬 필드는 keyword · long · boolean · date")
    void mapping() throws Exception {
        initializer.createIndexIfAbsent();

        var properties = client.indices().getMapping(request -> request.index(ProductIndexInitializer.ALIAS))
                .result().get(ProductIndexInitializer.INDEX_V1).mappings().properties();

        for (String field : List.of("title", "speciesName", "speciesAliases", "scientificName")) {
            Property property = properties.get(field);
            assertThat(property.text().analyzer()).as(field).isEqualTo("ko_text");
            assertThat(property.text().fields().get("compact").text().analyzer()).as(field).isEqualTo("ko_compact");
            // 오타 허용용: 공백으로만 자름 (#487)
            assertThat(property.text().fields().get("word").text().analyzer()).as(field).isEqualTo("ko_word");
        }
        assertThat(properties.get("description").text().analyzer()).isEqualTo("ko_text");
        assertThat(properties.get("productId").isLong()).isTrue();
        assertThat(properties.get("speciesId").isLong()).isTrue();
        assertThat(properties.get("price").isLong()).isTrue();
        for (String field : List.of("category", "light", "watering", "difficulty", "status")) {
            assertThat(properties.get(field).isKeyword()).as(field).isTrue();
        }
        assertThat(properties.get("hidden").isBoolean()).isTrue();
        assertThat(properties.get("createdAt").isDate()).isTrue();
    }

    @Test
    @DisplayName("이미 alias가 있으면 다시 만들지 않고 넘어간다 (앱을 다시 띄워도 오류 없음)")
    void skipsWhenAliasExists() throws Exception {
        initializer.createIndexIfAbsent();

        assertThatCode(() -> initializer.createIndexIfAbsent()).doesNotThrowAnyException();
        var aliases = client.indices().getAlias(request -> request.name(ProductIndexInitializer.ALIAS)).result();
        assertThat(aliases).containsOnlyKeys(ProductIndexInitializer.INDEX_V1);
    }

    @Test
    @DisplayName("정규화: \"몬 스테라\" · \"몬스테라\"는 같은 토큰 몬스테라")
    void normalizesKoreanSpacing() throws Exception {
        initializer.createIndexIfAbsent();

        assertThat(compactTokens("몬 스테라")).containsExactly("몬스테라");
        assertThat(compactTokens("몬스테라")).containsExactly("몬스테라");
    }

    // 한글 ↔ 영문("몬스테라" = "MONSTERA")은 정규화가 아니라 동의어라 #487에서 한다
    @Test
    @DisplayName("정규화: \"MONSTERA\" · \"monstera\" · \"Mon-stera\"는 같은 토큰 monstera")
    void normalizesCaseAndSymbols() throws Exception {
        initializer.createIndexIfAbsent();

        assertThat(compactTokens("MONSTERA")).containsExactly("monstera");
        assertThat(compactTokens("monstera")).containsExactly("monstera");
        assertThat(compactTokens("Mon-stera")).containsExactly("monstera");
    }

    @Test
    @DisplayName("OpenSearch에 연결할 수 없어도 앱 시작 시 초기화는 예외 없이 경고만 남긴다")
    void startupDoesNotFailWhenOpenSearchIsDown() throws Exception {
        OpenSearchConfig config = new OpenSearchConfig("http://localhost:1", Duration.ofSeconds(1), Duration.ofSeconds(2));
        ProductIndexInitializer unreachable =
                new ProductIndexInitializer(new ProductIndexAdmin(config.openSearchClient(config.openSearchTransport())));

        assertThatCode(unreachable::initialize).doesNotThrowAnyException();
        assertThat(unreachable.isReady()).isFalse();
        // 재시도도 같은 방식으로 예외 없이 넘어가고, 준비되지 않은 상태로 남는다
        assertThatCode(unreachable::retryUntilReady).doesNotThrowAnyException();
        assertThat(unreachable.isReady()).isFalse();
    }

    @Test
    @DisplayName("시작 때 실패해도 재시도에서 OpenSearch가 살아 있으면 인덱스 · alias를 만들고 준비 상태가 된다")
    void retryCreatesIndexWhenOpenSearchIsBack() throws Exception {
        initializer.retryUntilReady();

        assertThat(initializer.isReady()).isTrue();
        assertThat(client.indices().existsAlias(request -> request.name(ProductIndexInitializer.ALIAS)).value()).isTrue();
    }

    private List<String> compactTokens(String text) throws Exception {
        return client.indices().analyze(request -> request
                        .index(ProductIndexInitializer.ALIAS)
                        .analyzer("ko_compact")
                        .text(text))
                .tokens().stream().map(AnalyzeToken::token).toList();
    }
}
