package com.justcommit.backend.product.search.infrastructure;

import jakarta.json.JsonObject;
import jakarta.json.stream.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 앱이 뜰 때 상품 검색 인덱스를 준비한다.
 *
 * <p>앱은 항상 alias {@code products}로 읽고 쓰고, 실제 인덱스는 버전이 붙은 {@code products_v1}이다.
 * 분석기 · 매핑을 바꿀 때는 {@code products_v2}를 만들어 채운 뒤 alias만 옮긴다 (무중단 재색인, #487).
 *
 * <p>OpenSearch가 꺼져 있어도 앱은 뜬다. 원본은 DB이고 검색은 LIKE로 대신하기 때문 (docs/conventions/resilience.md).
 * 이때는 경고 로그를 남기고, 준비될 때까지 1분마다 다시 시도한다 (앱을 다시 띄우지 않아도 됨).
 */
@Slf4j
@Component
public class ProductIndexInitializer {

    public static final String ALIAS = "products";
    static final String INDEX_V1 = "products_v1";
    private static final String INDEX_V1_DEFINITION = "opensearch/products-v1.json";

    private final OpenSearchClient client;

    public ProductIndexInitializer(OpenSearchClient client) {
        this.client = client;
    }

    // 인덱스 · alias가 준비됐는지. 준비되면 재시도를 멈춘다
    private volatile boolean ready;
    // 실패 원인(스택)은 처음 한 번만 남기고, 재시도 중에는 한 줄만 (OpenSearch가 오래 꺼져 있을 때 로그가 넘치지 않게)
    private volatile boolean failureLogged;

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        try {
            createIndexIfAbsent();
            ready = true;
            failureLogged = false;
        } catch (Exception e) {
            if (failureLogged) {
                log.warn("상품 검색 인덱스를 아직 준비하지 못함 (1분 뒤 다시 시도): {}", e.toString());
            } else {
                log.warn("상품 검색 인덱스를 준비하지 못함 (OpenSearch 연결 확인, 검색은 LIKE로 대신, 1분마다 다시 시도)", e);
                failureLogged = true;
            }
        }
    }

    // 앱이 뜰 때 OpenSearch가 꺼져 있었다면, 준비될 때까지 다시 시도 (docs/reference/schedulers.md 「상품 검색 인덱스 준비」)
    @Scheduled(fixedDelayString = "${custom.search.index-init.retry-interval:PT1M}",
            initialDelayString = "${custom.search.index-init.retry-interval:PT1M}")
    public void retryUntilReady() {
        if (!ready) {
            initialize();
        }
    }

    boolean isReady() {
        return ready;
    }

    // alias가 이미 있으면 (v1이든 재색인한 v2든) 그대로 둔다. 없으면 products_v1을 만들면서 alias를 함께 붙인다
    void createIndexIfAbsent() throws IOException {
        if (client.indices().existsAlias(request -> request.name(ALIAS)).value()) {
            log.info("상품 검색 alias '{}'가 이미 있음", ALIAS);
            return;
        }

        JsonObject definition = readDefinition();
        JsonpMapper mapper = client._transport().jsonpMapper();
        IndexSettings settings = parse(mapper, definition.getJsonObject("settings"), IndexSettings.class);
        TypeMapping mappings = parse(mapper, definition.getJsonObject("mappings"), TypeMapping.class);

        try {
            // 인덱스 생성과 alias 연결을 한 요청으로: 인덱스만 있고 alias가 없는 중간 상태가 생기지 않음
            client.indices().create(request -> request
                    .index(INDEX_V1)
                    .settings(settings)
                    .mappings(mappings)
                    .aliases(ALIAS, alias -> alias));
            log.info("상품 검색 인덱스 '{}' 생성, alias '{}' 연결", INDEX_V1, ALIAS);
        } catch (OpenSearchException e) {
            // 서버 두 대가 동시에 떠서 다른 쪽이 먼저 만든 경우
            if ("resource_already_exists_exception".equals(e.error().type())) {
                log.info("상품 검색 인덱스 '{}'를 다른 인스턴스가 먼저 만듦", INDEX_V1);
                return;
            }
            throw e;
        }
    }

    // 분석기 · 매핑은 JSON 파일에 두고 (OpenSearch 문서의 예시와 같은 모양), 클라이언트 타입으로 바꿔서 보낸다
    private JsonObject readDefinition() throws IOException {
        try (InputStream in = new ClassPathResource(INDEX_V1_DEFINITION).getInputStream();
             JsonParser parser = client._transport().jsonpMapper().jsonProvider().createParser(in)) {
            parser.next();
            return parser.getObject();
        }
    }

    private static <T> T parse(JsonpMapper mapper, JsonObject json, Class<T> type) {
        try (JsonParser parser = mapper.jsonProvider().createParser(new StringReader(json.toString()))) {
            return mapper.deserialize(parser, type);
        }
    }
}
