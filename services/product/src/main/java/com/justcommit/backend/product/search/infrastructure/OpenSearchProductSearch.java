package com.justcommit.backend.product.search.infrastructure;

import com.justcommit.backend.product.search.application.ProductDocument;
import com.justcommit.backend.product.search.application.ProductKeywordSearch;
import com.justcommit.backend.product.search.application.ProductSearchSort;
import com.justcommit.backend.product.search.application.ProductSearchUnavailableException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.Operator;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.stereotype.Component;

/**
 * alias {@code products}에서 키워드로 상품을 찾는다 (#486).
 *
 * <p>쿼리 = bool { should: 키워드 (점수), filter: 노출 규칙 · 필터 (점수 없이 거르기만) }.
 * 찾는 곳 · 가중치는 제목 3 > 품종명 · 별칭 · 학명 2 > 설명 1 (docs/flows/search-recommend.md 「찾는 곳」 · 「정렬」).
 * 각 필드는 {@code .compact} 하위 필드(공백 · 기호를 지운 분석기)도 같은 가중치로 찾아 "몬 스테라"가 "몬스테라"에 걸리게 한다.
 * 별칭은 품종에 붙은 것이라, 별칭이 여러 품종에 걸리면 그 품종들의 상품이 모두 나온다.
 *
 * <p>오타 허용(#487)은 {@code .word} 하위 필드(공백으로만 자름)에서 한다. 한국어 분석기는 모르는 단어를 다르게 잘라
 * ("몬스테러" → 몬스 + 테러) 원래 토큰과 비교할 수 없기 때문. 단어 길이로 허용 글자 수가 정해진다
 * ({@code AUTO:3,6}: 1 ~ 2글자는 정확히, 3 ~ 5글자는 1글자, 6글자 이상은 2글자). 오타 허용은 정확히 맞는 것보다
 * 점수가 낮도록 가중치를 절반으로 둔다. 설명은 길어 엉뚱한 단어가 걸리기 쉬워 오타를 허용하지 않는다.
 *
 * <p>연결 실패 · 시간 초과는 1번 더 보낸다 (docs/conventions/resilience.md product → OpenSearch 재시도 1회).
 * 그래도 실패하거나 OpenSearch가 오류로 답하면 {@link ProductSearchUnavailableException} (서비스가 LIKE로 대신).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenSearchProductSearch implements ProductKeywordSearch {

    static final float TITLE_BOOST = 3f;
    // 품종명 · 별칭 · 학명은 모두 「어떤 품종인가」를 가리키므로 같은 가중치
    static final float SPECIES_BOOST = 2f;
    static final float DESCRIPTION_BOOST = 1f;
    // 오타 허용 매칭은 정확히 맞는 것보다 낮게
    static final float TYPO_FACTOR = 0.5f;
    static final String TYPO_FUZZINESS = "AUTO:3,6";
    private static final int MAX_ATTEMPTS = 2;
    // OpenSearch index.max_result_window 기본값. from + size가 이보다 크면 OpenSearch가 오류로 답한다
    static final int MAX_RESULT_WINDOW = 10_000;

    private final OpenSearchClient client;

    @Override
    public Result search(Condition condition, ProductSearchSort sort, int page, int size) {
        // 너무 깊은 페이지(페이지 크기 50이면 약 200페이지부터)는 오류 → LIKE 대체로 넘어가 정렬이 바뀌므로,
        // 장애로 보지 않고 전체 개수만 세어 빈 페이지로 답한다 (#547 리뷰)
        boolean beyondWindow = (long) page * size + size > MAX_RESULT_WINDOW;
        SearchRequest request = SearchRequest.of(search -> search
                .index(ProductIndexInitializer.ALIAS)
                .query(query(condition))
                .sort(sortOptions(sort))
                .from(beyondWindow ? 0 : page * size)
                .size(beyondWindow ? 0 : size)
                // 기본은 10,000건까지만 세므로, 화면의 전체 개수(판매 완료 개수 등)가 정확하도록 끝까지 셈
                .trackTotalHits(total -> total.enabled(true))
                // id만 쓰고 화면 값은 DB에서 읽으므로 문서 본문은 받지 않음
                .source(source -> source.fetch(false)));
        SearchResponse<ProductDocument> response = execute(request);
        List<Long> ids = response.hits().hits().stream().map(hit -> Long.valueOf(hit.id())).toList();
        long total = response.hits().total() == null ? ids.size() : response.hits().total().value();
        return new Result(ids, total);
    }

    private SearchResponse<ProductDocument> execute(SearchRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return client.search(request, ProductDocument.class);
            } catch (IOException e) {
                // 연결 실패 · 읽기 시간 초과 (HTTP 클라이언트가 IOException으로 줌)
                if (attempt >= MAX_ATTEMPTS) {
                    throw new ProductSearchUnavailableException("OpenSearch 검색 연결 실패", e);
                }
                log.info("OpenSearch 검색 실패, 한 번 더 보냄: {}", e.toString());
            } catch (OpenSearchException e) {
                // OpenSearch가 오류로 답함 (인덱스 없음 · 서버 오류). 다시 보내도 같을 가능성이 커서 재시도하지 않음
                throw new ProductSearchUnavailableException("OpenSearch 검색 오류: " + e.error().type(), e);
            }
        }
    }

    static Query query(Condition condition) {
        String keyword = condition.keyword();
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.terms(terms -> terms.field("status").terms(values -> values.value(
                condition.statuses().stream().map(status -> FieldValue.of(status.name())).toList())))));
        filters.add(term("hidden", FieldValue.of(false)));
        if (condition.speciesId() != null) {
            filters.add(term("speciesId", FieldValue.of(condition.speciesId())));
        }
        addKeywordFilter(filters, "category", condition.category());
        addKeywordFilter(filters, "light", condition.light());
        addKeywordFilter(filters, "watering", condition.watering());
        addKeywordFilter(filters, "difficulty", condition.difficulty());
        if (condition.minPrice() != null || condition.maxPrice() != null) {
            filters.add(Query.of(q -> q.range(range -> {
                range.field("price");
                if (condition.minPrice() != null) {
                    range.gte(price(condition.minPrice()));
                }
                if (condition.maxPrice() != null) {
                    range.lte(price(condition.maxPrice()));
                }
                return range;
            })));
        }

        List<Query> should = List.of(
                match("title", keyword, TITLE_BOOST),
                match("title.compact", keyword, TITLE_BOOST),
                match("speciesName", keyword, SPECIES_BOOST),
                match("speciesName.compact", keyword, SPECIES_BOOST),
                match("speciesAliases", keyword, SPECIES_BOOST),
                match("speciesAliases.compact", keyword, SPECIES_BOOST),
                match("scientificName", keyword, SPECIES_BOOST),
                match("scientificName.compact", keyword, SPECIES_BOOST),
                match("description", keyword, DESCRIPTION_BOOST),
                typo("title.word", keyword, TITLE_BOOST),
                typo("speciesName.word", keyword, SPECIES_BOOST),
                typo("speciesAliases.word", keyword, SPECIES_BOOST),
                typo("scientificName.word", keyword, SPECIES_BOOST));

        return Query.of(q -> q.bool(bool -> bool
                .filter(filters)
                .should(should)
                // filter가 있으면 should가 선택이 되므로, 키워드가 한 곳이라도 맞아야 결과에 들게
                .minimumShouldMatch("1")));
    }

    // 검색어의 단어가 모두 그 필드에 있어야 맞음 (operator AND). "몬스테라 화분"이 "화분"만 있는 상품까지 끌어오지 않게
    private static Query match(String field, String keyword, float boost) {
        return Query.of(q -> q.match(match -> match
                .field(field)
                .query(FieldValue.of(keyword))
                .operator(Operator.And)
                .boost(boost)));
    }

    // 오타 허용: 공백으로 자른 단어마다 길이에 따라 1 ~ 2글자 다른 것도 맞음. 단어가 모두 맞아야 함(AND)
    private static Query typo(String field, String keyword, float boost) {
        return Query.of(q -> q.match(match -> match
                .field(field)
                .query(FieldValue.of(keyword))
                .operator(Operator.And)
                .fuzziness(TYPO_FUZZINESS)
                .boost(boost * TYPO_FACTOR)));
    }

    private static Query term(String field, FieldValue value) {
        return Query.of(q -> q.term(term -> term.field(field).value(value)));
    }

    private static void addKeywordFilter(List<Query> filters, String field, String value) {
        if (value != null) {
            filters.add(term(field, FieldValue.of(value)));
        }
    }

    // 가격은 원 단위 정수 (문서는 long)
    private static JsonData price(BigDecimal value) {
        return JsonData.of(value);
    }

    // 동점이면 최신 · 상품 id 순으로 (페이지를 넘겨도 순서가 흔들리지 않게)
    static List<SortOptions> sortOptions(ProductSearchSort sort) {
        List<SortOptions> options = new ArrayList<>();
        switch (sort) {
            case RELEVANCE -> options.add(SortOptions.of(s -> s.score(score -> score.order(SortOrder.Desc))));
            case PRICE_ASC -> options.add(field("price", SortOrder.Asc));
            case PRICE_DESC -> options.add(field("price", SortOrder.Desc));
            case LATEST -> {
            }
        }
        options.add(field("createdAt", SortOrder.Desc));
        options.add(field("productId", SortOrder.Desc));
        return options;
    }

    private static SortOptions field(String field, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(order)));
    }
}
