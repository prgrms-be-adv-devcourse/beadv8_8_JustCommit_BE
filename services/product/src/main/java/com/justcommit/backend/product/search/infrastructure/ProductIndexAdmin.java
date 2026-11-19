package com.justcommit.backend.product.search.infrastructure;

import jakarta.json.JsonObject;
import jakarta.json.stream.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 상품 검색 인덱스 관리: 만들기 · alias가 가리키는 인덱스 찾기 · alias 옮기기 · 지우기.
 *
 * <p>인덱스 이름은 {@code products_v{번호}}이고, 분석기 · 매핑은 번호와 상관없이 항상 지금 코드의
 * {@code resources/opensearch/products.json}으로 만든다. 처음엔 {@code products_v1}, 무중단 재색인(#487)마다 번호가 1씩 오른다.
 */
@Component
@RequiredArgsConstructor
public class ProductIndexAdmin {

    public static final String ALIAS = "products";
    static final String FIRST_INDEX = "products_v1";
    private static final String DEFINITION = "opensearch/products.json";
    private static final Pattern INDEX_NAME = Pattern.compile("^" + ALIAS + "_v(\\d+)$");

    private final OpenSearchClient client;

    public boolean aliasExists() throws IOException {
        return client.indices().existsAlias(request -> request.name(ALIAS)).value();
    }

    // alias가 가리키는 실제 인덱스. 없으면 비어 있음 (재색인 도중 실패로 둘 이상이면 가장 높은 번호)
    public Optional<String> currentIndex() throws IOException {
        if (!aliasExists()) {
            return Optional.empty();
        }
        List<String> indices = client.indices().getAlias(request -> request.name(ALIAS)).result().keySet().stream()
                .sorted((a, b) -> Integer.compare(numberOf(b), numberOf(a)))
                .toList();
        return indices.stream().findFirst();
    }

    // products_v3 → products_v4. 규칙에 맞지 않는 이름이면 v2부터
    public static String nextIndexName(String current) {
        return ALIAS + "_v" + (Math.max(numberOf(current), 1) + 1);
    }

    private static int numberOf(String index) {
        Matcher matcher = INDEX_NAME.matcher(index);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /**
     * 지금 매핑으로 인덱스를 만든다. withAlias면 생성과 alias 연결을 한 요청으로 (인덱스만 있고 alias가 없는 중간 상태가 없음).
     * 같은 이름이 이미 있으면 OpenSearchException(resource_already_exists_exception).
     */
    public void create(String index, boolean withAlias) throws IOException {
        JsonObject definition = readDefinition();
        JsonpMapper mapper = client._transport().jsonpMapper();
        IndexSettings settings = parse(mapper, definition.getJsonObject("settings"), IndexSettings.class);
        TypeMapping mappings = parse(mapper, definition.getJsonObject("mappings"), TypeMapping.class);
        client.indices().create(request -> {
            request.index(index).settings(settings).mappings(mappings);
            if (withAlias) {
                request.aliases(ALIAS, alias -> alias);
            }
            return request;
        });
    }

    // alias를 from에서 to로 한 요청으로 옮긴다. 두 동작이 한꺼번에 반영돼 검색이 빈 alias를 보는 순간이 없음
    public void switchAlias(String from, String to) throws IOException {
        client.indices().updateAliases(request -> request
                .actions(action -> action.remove(remove -> remove.index(from).alias(ALIAS)))
                .actions(action -> action.add(add -> add.index(to).alias(ALIAS))));
    }

    public void delete(String index) throws IOException {
        client.indices().delete(request -> request.index(index));
    }

    // 분석기 · 매핑은 JSON 파일에 두고 (OpenSearch 문서의 예시와 같은 모양), 클라이언트 타입으로 바꿔서 보낸다
    private JsonObject readDefinition() throws IOException {
        try (InputStream in = new ClassPathResource(DEFINITION).getInputStream();
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
