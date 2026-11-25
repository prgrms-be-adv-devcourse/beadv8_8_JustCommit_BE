package com.justcommit.backend.market.review.presentation;

import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.client.product.ProductQuery;
import com.justcommit.backend.market.client.settlement.SettlementQuery;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.order.application.OrderReadService;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.review.application.ReviewReadService;
import com.justcommit.backend.market.review.application.ReviewService;
import com.justcommit.backend.market.review.infrastructure.ReviewRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 리뷰 작성 API를 HTTP → 컨트롤러 → 실제 ReviewService 공개 create → 실제 주문 조회 → Postgres로 확인한다 (#582).
// 서비스를 목으로 두지 않는다. JSON 변환은 운영과 같은 Spring Boot Jackson 설정(JsonMapper)을 쓴다.
// 인증(401)은 보안 필터를 거치는 ReviewSecurityTest에서 본다
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({JpaAuditingConfig.class, OrderReadService.class, ReviewService.class, ReviewReadService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewControllerIntegrationTest {

  private static final long BUYER_ID = 10L;
  private static final long SELLER_ID = 20L;
  private static final String CONTENT = "잎이 싱싱하고 포장이 꼼꼼했어요";

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
  }

  // OrderReadService의 다른 조회가 쓰는 의존성. 리뷰 대상 조회는 쓰지 않는다
  @MockitoBean MemberQuery memberQuery;
  @MockitoBean ShipmentQuery shipmentQuery;
  @MockitoBean SettlementQuery settlementQuery;
  @MockitoBean ProductQuery productQuery;

  @MockitoSpyBean ReviewRepository reviewRepository;

  @Autowired ReviewService reviewService;
  @Autowired ReviewReadService reviewReadService;
  @Autowired OrdersRepository ordersRepository;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired JsonMapper jsonMapper;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new ReviewController(reviewService, reviewReadService))
      .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
      .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
      new AuthMember(BUYER_ID, "MEMBER"), null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
  }

  @AfterEach
  void cleanUp() {
    SecurityContextHolder.clearContext();
    jdbcTemplate.execute("truncate table review_tag, review, return_request, orders_item, seller_order, orders");
  }

  @Test
  @DisplayName("리뷰를 쓰면 201과 reviewId를 돌려주고, 공개 create가 연 트랜잭션에서 리뷰 · 태그를 저장한다")
  void createReturnsCreated() throws Exception {
    long sellerOrderId = sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "CONFIRMED", "CANCELLED");
    List<String> transactionNames = Collections.synchronizedList(new ArrayList<>());
    List<Boolean> readOnlyFlags = Collections.synchronizedList(new ArrayList<>());
    Answer<?> real = mockingDetails(reviewRepository).getMockCreationSettings().getDefaultAnswer();
    doAnswer(invocation -> {
      transactionNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
      readOnlyFlags.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
      return real.answer(invocation);
    }).when(reviewRepository).existsBySellerOrderId(sellerOrderId);
    // 테스트 자체의 트랜잭션이 없어야 아래 트랜잭션 이름이 서비스가 연 것임을 보장한다
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

    ResultActions result = create("""
      {"sellerOrderId": %d, "rating": 4.5, "content": "  %s  ", "tags": ["CAREFUL_PACKING", "HEALTHY_ARRIVAL"]}
      """.formatted(sellerOrderId, CONTENT));

    result.andExpect(status().isCreated())
      .andExpect(jsonPath("$.status").value(201))
      .andExpect(jsonPath("$.message").value("리뷰를 작성했습니다."))
      .andExpect(jsonPath("$.data.reviewId").isNumber());
    Long reviewId = jdbcTemplate.queryForObject("select id from review where seller_order_id = ?", Long.class,
      sellerOrderId);
    assertThat(transactionNames).singleElement().asString().endsWith("ReviewService.create");
    assertThat(readOnlyFlags).containsExactly(false);
    assertThat(jdbcTemplate.queryForObject("select content from review where id = ?", String.class, reviewId))
      .isEqualTo(CONTENT);
    assertThat(jdbcTemplate.queryForObject("select rating from review where id = ?", BigDecimal.class, reviewId))
      .isEqualByComparingTo("4.5");
    assertThat(jdbcTemplate.queryForList("select tag from review_tag where review_id = ? order by tag",
      String.class, reviewId)).containsExactly("CAREFUL_PACKING", "HEALTHY_ARRIVAL");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ", \"tags\": null", ", \"tags\": []"})
  @DisplayName("태그를 생략하거나 null · 빈 목록으로 보내면 태그 없이 저장한다")
  void tagsOmittedOrNullOrEmpty(String tagsPart) throws Exception {
    long sellerOrderId = sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "CONFIRMED");

    create("""
      {"sellerOrderId": %d, "rating": 5.0, "content": "%s"%s}
      """.formatted(sellerOrderId, CONTENT, tagsPart))
      .andExpect(status().isCreated());

    assertThat(jdbcTemplate.queryForObject("select count(*) from review", Long.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("select count(*) from review_tag", Long.class)).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "\"rating\": 2.3, \"content\": \"" + CONTENT + "\"",
    "\"rating\": 4.5, \"content\": \"가나다라마바사아자\"",
    "\"rating\": 4.5, \"content\": \"   가나다라마바사아자   \"",
    "\"rating\": 4.5, \"content\": \"" + CONTENT + "\", \"tags\": [\"GOOD_PRICE\", null]",
    "\"rating\": 4.5, \"content\": \"" + CONTENT + "\", \"tags\": [\"GOOD_PRICE\", \"GOOD_PRICE\"]",
    "\"content\": \"" + CONTENT + "\"",
    "\"rating\": 4.5"
  })
  @DisplayName("별점 2.3 · 글 9자 · 태그 안 null · 중복 태그 · 필수 값 누락은 400 INVALID_FIELD_ERROR이고 저장하지 않는다")
  void invalidFieldIsBadRequest(String fields) throws Exception {
    long sellerOrderId = sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "CONFIRMED");

    create("{\"sellerOrderId\": %d, %s}".formatted(sellerOrderId, fields))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));

    assertThat(jdbcTemplate.queryForObject("select count(*) from review", Long.class)).isZero();
  }

  @Test
  @DisplayName("글이 301자면 400 INVALID_FIELD_ERROR, sellerOrderId가 없으면 400 INVALID_FIELD_ERROR")
  void tooLongContentAndMissingSellerOrderId() throws Exception {
    long sellerOrderId = sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "CONFIRMED");

    create("""
      {"sellerOrderId": %d, "rating": 4.5, "content": "%s"}
      """.formatted(sellerOrderId, "가".repeat(301)))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
    create("""
      {"rating": 4.5, "content": "%s"}
      """.formatted(CONTENT))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));

    assertThat(jdbcTemplate.queryForObject("select count(*) from review", Long.class)).isZero();
  }

  // 없는 태그 이름과 숫자(enum 순번으로 바뀌면 안 된다)는 JSON을 읽지 못한 것으로 보고 공통 처리대로 BAD_REQUEST_DATA
  @ParameterizedTest
  @ValueSource(strings = {"[\"FOO\"]", "[0]", "[4]", "[\"0\"]", "[\"good_price\"]"})
  @DisplayName("없는 태그 이름, 숫자 태그(순번), 숫자 문자열, 소문자 태그는 400 BAD_REQUEST_DATA이고 저장하지 않는다")
  void unknownOrNumericTagIsBadRequestData(String tags) throws Exception {
    long sellerOrderId = sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "CONFIRMED");

    create("""
      {"sellerOrderId": %d, "rating": 4.5, "content": "%s", "tags": %s}
      """.formatted(sellerOrderId, CONTENT, tags))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("BAD_REQUEST_DATA"));

    assertThat(jdbcTemplate.queryForObject("select count(*) from review", Long.class)).isZero();
  }

  @Test
  @DisplayName("남의 판매자 주문과 없는 판매자 주문은 같은 404 REVIEW_SELLER_ORDER_NOT_FOUND")
  void anotherBuyersOrMissingSellerOrderIsNotFound() throws Exception {
    long othersSellerOrderId = sellerOrder(99L, LocalDateTime.now().minusDays(1), "CONFIRMED");

    createFor(othersSellerOrderId)
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value("REVIEW_SELLER_ORDER_NOT_FOUND"));
    createFor(othersSellerOrderId + 1_000)
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value("REVIEW_SELLER_ORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("구매 확정 전이면 409 REVIEW_NOT_CONFIRMED")
  void notConfirmedIsConflict() throws Exception {
    createFor(sellerOrder(BUYER_ID, null, "NORMAL"))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value("REVIEW_NOT_CONFIRMED"));
  }

  @Test
  @DisplayName("전부 반품된 판매자 주문은 409 REVIEW_NO_CONFIRMED_ITEM")
  void allReturnedIsConflict() throws Exception {
    createFor(sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "RETURNED", "RETURNED"))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value("REVIEW_NO_CONFIRMED_ITEM"));
  }

  @Test
  @DisplayName("구매 확정 후 3일이 지났으면 409 REVIEW_PERIOD_EXPIRED")
  void periodExpiredIsConflict() throws Exception {
    createFor(sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(3).minusMinutes(1), "CONFIRMED"))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value("REVIEW_PERIOD_EXPIRED"));
  }

  @Test
  @DisplayName("같은 판매자 주문에 두 번 쓰면 409 REVIEW_ALREADY_WRITTEN이고 1건만 남는다")
  void duplicateIsConflict() throws Exception {
    long sellerOrderId = sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "CONFIRMED");
    createFor(sellerOrderId).andExpect(status().isCreated());

    createFor(sellerOrderId)
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_WRITTEN"));
    assertThat(jdbcTemplate.queryForObject("select count(*) from review", Long.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("삭제한 리뷰가 있는 판매자 주문에 다시 쓰면 409 REVIEW_ALREADY_WRITTEN")
  void rewriteAfterDeleteIsConflict() throws Exception {
    long sellerOrderId = sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "CONFIRMED");
    createFor(sellerOrderId).andExpect(status().isCreated());
    jdbcTemplate.update("update review set deleted_at = now() where seller_order_id = ?", sellerOrderId);

    createFor(sellerOrderId)
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_WRITTEN"));
    assertThat(jdbcTemplate.queryForObject("select count(*) from review", Long.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("판매자 리뷰 목록: 200 ApiResponse의 data에 PagingResponse, 리뷰마다 태그 · 확정 상품명 · 작성자 닉네임, 기본 20개")
  void sellerReviewList() throws Exception {
    long sellerOrderId = sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "CONFIRMED", "CANCELLED");
    long reviewId = insertReview(sellerOrderId, "4.5", "CAREFUL_PACKING", "AS_DESCRIBED");
    when(memberQuery.getNicknames(anyCollection())).thenReturn(Map.of(BUYER_ID, "초록손"));

    mockMvc.perform(get("/api/v1/reviews").param("sellerId", String.valueOf(SELLER_ID)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").value(200))
      .andExpect(jsonPath("$.message").value("판매자 리뷰를 조회했습니다."))
      .andExpect(jsonPath("$.data.page").value(0))
      .andExpect(jsonPath("$.data.size").value(20))
      .andExpect(jsonPath("$.data.totalElements").value(1))
      .andExpect(jsonPath("$.data.content[0].reviewId").value(reviewId))
      .andExpect(jsonPath("$.data.content[0].rating").value(4.5))
      .andExpect(jsonPath("$.data.content[0].content").value(CONTENT))
      .andExpect(jsonPath("$.data.content[0].tags[0]").value("AS_DESCRIBED"))
      .andExpect(jsonPath("$.data.content[0].tags[1]").value("CAREFUL_PACKING"))
      .andExpect(jsonPath("$.data.content[0].createdAt").isNotEmpty())
      .andExpect(jsonPath("$.data.content[0].writerNickname").value("초록손"))
      .andExpect(jsonPath("$.data.content[0].productNames.length()").value(1))
      .andExpect(jsonPath("$.data.content[0].productNames[0]").value("몬스테라0"));
  }

  @Test
  @DisplayName("판매자 리뷰 목록: size는 최대 50, 리뷰가 없는 판매자는 빈 목록")
  void sellerReviewListPageSizeAndEmpty() throws Exception {
    mockMvc.perform(get("/api/v1/reviews").param("sellerId", "999").param("size", "100"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.size").value(50))
      .andExpect(jsonPath("$.data.content.length()").value(0))
      .andExpect(jsonPath("$.data.totalElements").value(0));
  }

  @Test
  @DisplayName("판매자 리뷰 집계: 200 ApiResponse, 별점 10구간(5.0 → 0.5) · 태그 5종(enum 순서), 0건이면 평균 null")
  void sellerReviewSummary() throws Exception {
    insertReview(sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "CONFIRMED"), "4.0", "GOOD_PRICE");
    insertReview(sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "CONFIRMED"), "4.5");

    mockMvc.perform(get("/api/v1/reviews/summary").param("sellerId", String.valueOf(SELLER_ID)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.message").value("판매자 리뷰 집계를 조회했습니다."))
      .andExpect(jsonPath("$.data.averageRating").value(4.3))
      .andExpect(jsonPath("$.data.reviewCount").value(2))
      .andExpect(jsonPath("$.data.ratingCounts.length()").value(10))
      .andExpect(jsonPath("$.data.ratingCounts[0].rating").value(5.0))
      .andExpect(jsonPath("$.data.ratingCounts[0].count").value(0))
      .andExpect(jsonPath("$.data.ratingCounts[1].rating").value(4.5))
      .andExpect(jsonPath("$.data.ratingCounts[1].count").value(1))
      .andExpect(jsonPath("$.data.ratingCounts[2].count").value(1))
      .andExpect(jsonPath("$.data.ratingCounts[9].rating").value(0.5))
      .andExpect(jsonPath("$.data.tagCounts.length()").value(5))
      .andExpect(jsonPath("$.data.tagCounts[0].tag").value("AS_DESCRIBED"))
      .andExpect(jsonPath("$.data.tagCounts[4].tag").value("GOOD_PRICE"))
      .andExpect(jsonPath("$.data.tagCounts[4].count").value(1));
    mockMvc.perform(get("/api/v1/reviews/summary").param("sellerId", "999"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.averageRating").value(nullValue()))
      .andExpect(jsonPath("$.data.reviewCount").value(0));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/reviews", "/api/v1/reviews/summary"})
  @DisplayName("판매자 리뷰 목록 · 집계: sellerId 누락 · 빈 값은 400 MISSING_PARAMETER, 형식 오류는 400 TYPE_MISMATCH")
  void sellerIdIsRequiredNumber(String path) throws Exception {
    mockMvc.perform(get(path))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    mockMvc.perform(get(path).param("sellerId", ""))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    mockMvc.perform(get(path).param("sellerId", "abc"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"));
  }

  @Test
  @DisplayName("내 리뷰 목록 (#584): 200 ApiResponse의 data에 PagingResponse, 판매자 닉네임 · deletableUntil 포함")
  void myReviewList() throws Exception {
    long sellerOrderId = sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "CONFIRMED");
    long reviewId = insertReview(sellerOrderId, "4.5", "GOOD_PRICE");
    insertReviewBy(sellerOrder(99L, LocalDateTime.now().minusDays(1), "CONFIRMED"), 99L, LocalDateTime.now()); // 남의 리뷰
    when(memberQuery.getNicknames(anyCollection())).thenReturn(Map.of(SELLER_ID, "베란다정원"));

    mockMvc.perform(get("/api/v1/reviews/me"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.message").value("내 리뷰를 조회했습니다."))
      .andExpect(jsonPath("$.data.totalElements").value(1))
      .andExpect(jsonPath("$.data.content[0].reviewId").value(reviewId))
      .andExpect(jsonPath("$.data.content[0].sellerId").value(SELLER_ID))
      .andExpect(jsonPath("$.data.content[0].sellerNickname").value("베란다정원"))
      .andExpect(jsonPath("$.data.content[0].orderedAt").isNotEmpty())
      .andExpect(jsonPath("$.data.content[0].tags[0]").value("GOOD_PRICE"))
      .andExpect(jsonPath("$.data.content[0].productNames[0]").value("몬스테라0"))
      .andExpect(jsonPath("$.data.content[0].createdAt").isNotEmpty())
      .andExpect(jsonPath("$.data.content[0].deletableUntil").isNotEmpty());
  }

  @Test
  @DisplayName("리뷰 삭제 (#584): 200 ApiResponse(메시지만), deleted_at 기록 · 행 유지. 내 리뷰 · 판매자 목록 · 집계에서 빠지고 같은 판매자 주문에 다시 쓰면 409")
  void deleteThenExcludedEverywhereAndCannotRewrite() throws Exception {
    long sellerOrderId = sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "CONFIRMED");
    createFor(sellerOrderId).andExpect(status().isCreated());
    Long reviewId = jdbcTemplate.queryForObject("select id from review where seller_order_id = ?", Long.class, sellerOrderId);

    mockMvc.perform(delete("/api/v1/reviews/{reviewId}", reviewId))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").value(200))
      .andExpect(jsonPath("$.message").value("리뷰를 삭제했습니다."))
      .andExpect(jsonPath("$.data").doesNotExist());

    assertThat(jdbcTemplate.queryForObject("select deleted_at from review where id = ?", LocalDateTime.class, reviewId))
      .isNotNull();
    assertThat(jdbcTemplate.queryForObject("select count(*) from review", Long.class)).isEqualTo(1);
    mockMvc.perform(get("/api/v1/reviews/me"))
      .andExpect(jsonPath("$.data.totalElements").value(0));
    mockMvc.perform(get("/api/v1/reviews").param("sellerId", String.valueOf(SELLER_ID)))
      .andExpect(jsonPath("$.data.totalElements").value(0));
    mockMvc.perform(get("/api/v1/reviews/summary").param("sellerId", String.valueOf(SELLER_ID)))
      .andExpect(jsonPath("$.data.reviewCount").value(0))
      .andExpect(jsonPath("$.data.averageRating").value(nullValue()))
      .andExpect(jsonPath("$.data.ratingCounts[1].count").value(0))
      .andExpect(jsonPath("$.data.tagCounts[4].count").value(0));
    createFor(sellerOrderId)
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_WRITTEN"));
  }

  @Test
  @DisplayName("리뷰 삭제: 없는 리뷰 · 다른 작성자의 리뷰 · 이미 삭제된 리뷰는 모두 404 REVIEW_NOT_FOUND이고 아무것도 바뀌지 않는다")
  void deleteNotFoundCases() throws Exception {
    long othersReviewId = insertReviewBy(sellerOrder(99L, LocalDateTime.now().minusDays(1), "CONFIRMED"), 99L,
      LocalDateTime.now());
    long deletedReviewId = insertReviewBy(sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "CONFIRMED"), BUYER_ID,
      LocalDateTime.now());
    LocalDateTime firstDeletedAt = LocalDateTime.now().minusMinutes(5).withNano(0);
    jdbcTemplate.update("update review set deleted_at = ? where id = ?", firstDeletedAt, deletedReviewId);

    for (long reviewId : new long[] {othersReviewId + 1_000, othersReviewId, deletedReviewId}) {
      mockMvc.perform(delete("/api/v1/reviews/{reviewId}", reviewId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }
    assertThat(jdbcTemplate.queryForObject("select deleted_at from review where id = ?", LocalDateTime.class,
      othersReviewId)).isNull();
    assertThat(jdbcTemplate.queryForObject("select deleted_at from review where id = ?", LocalDateTime.class,
      deletedReviewId)).isEqualTo(firstDeletedAt);
  }

  @Test
  @DisplayName("리뷰 삭제: 작성 후 72시간이 지났으면 409 REVIEW_DELETE_PERIOD_EXPIRED이고 삭제하지 않는다")
  void deleteAfterPeriodIsConflict() throws Exception {
    long reviewId = insertReviewBy(sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(5), "CONFIRMED"), BUYER_ID,
      LocalDateTime.now().minusHours(72).minusMinutes(1));

    mockMvc.perform(delete("/api/v1/reviews/{reviewId}", reviewId))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value("REVIEW_DELETE_PERIOD_EXPIRED"));
    assertThat(jdbcTemplate.queryForObject("select deleted_at from review where id = ?", LocalDateTime.class, reviewId))
      .isNull();
  }

  // 매핑이 없을 때의 응답 코드는 standalone MockMvc(정적 리소스 핸들러 없음)와 실제 앱이 달라 보지 않고, 삭제 메서드로 가지 않는 것만 본다
  @Test
  @DisplayName("리뷰 삭제: reviewId가 숫자가 아니면 삭제 메서드로 매핑되지 않는다 (reviews/me는 GET만 있어 405)")
  void deleteRequiresNumericReviewId() throws Exception {
    mockMvc.perform(delete("/api/v1/reviews/me"))
      .andExpect(status().isMethodNotAllowed());
    assertThat(mockMvc.perform(delete("/api/v1/reviews/abc")).andReturn().getHandler()).isNull();
  }

  @Test
  @DisplayName("작성 가능한 주문 목록 (#585): 200 ApiResponse의 data에 배열, 리뷰를 쓰면 빠지고 실제 삭제 API로 지워도 다시 나오지 않는다")
  void writableListThenWriteAndDelete() throws Exception {
    long sellerOrderId = sellerOrder(BUYER_ID, LocalDateTime.now().minusDays(1), "CONFIRMED", "CANCELLED");
    when(memberQuery.getNicknames(anyCollection())).thenReturn(Map.of(SELLER_ID, "베란다정원"));

    mockMvc.perform(get("/api/v1/reviews/writable"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.message").value("작성 가능한 주문을 조회했습니다."))
      .andExpect(jsonPath("$.data.length()").value(1))
      .andExpect(jsonPath("$.data[0].sellerOrderId").value(sellerOrderId))
      .andExpect(jsonPath("$.data[0].sellerId").value(SELLER_ID))
      .andExpect(jsonPath("$.data[0].sellerNickname").value("베란다정원"))
      .andExpect(jsonPath("$.data[0].orderedAt").isNotEmpty())
      .andExpect(jsonPath("$.data[0].confirmedAt").isNotEmpty())
      .andExpect(jsonPath("$.data[0].writableUntil").isNotEmpty())
      .andExpect(jsonPath("$.data[0].productNames.length()").value(1))
      .andExpect(jsonPath("$.data[0].productNames[0]").value("몬스테라0"));

    createFor(sellerOrderId).andExpect(status().isCreated());
    mockMvc.perform(get("/api/v1/reviews/writable"))
      .andExpect(jsonPath("$.data.length()").value(0));

    Long reviewId = jdbcTemplate.queryForObject("select id from review where seller_order_id = ?", Long.class, sellerOrderId);
    mockMvc.perform(delete("/api/v1/reviews/{reviewId}", reviewId)).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/reviews/writable"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data").isArray())
      .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  @DisplayName("작성 가능한 주문 목록: 쓸 수 있는 주문이 없으면 빈 배열")
  void writableListEmpty() throws Exception {
    sellerOrder(BUYER_ID, null, "NORMAL"); // 구매 확정 전

    mockMvc.perform(get("/api/v1/reviews/writable"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data").isArray())
      .andExpect(jsonPath("$.data.length()").value(0));
  }

  // 작성자 · 작성 시각을 정해 리뷰를 DB에 직접 넣는다 (판매자 20, 별점 4.5, 태그 없음)
  private long insertReviewBy(long sellerOrderId, long buyerId, LocalDateTime createdAt) {
    return jdbcTemplate.queryForObject("""
      insert into review (seller_order_id, order_id, buyer_id, seller_id, rating, content, created_at, updated_at)
      values (?, (select order_id from seller_order where id = ?), ?, ?, 4.5, ?, ?, ?) returning id
      """, Long.class, sellerOrderId, sellerOrderId, buyerId, SELLER_ID, CONTENT, createdAt, createdAt);
  }

  // 목록 · 집계용 리뷰를 DB에 직접 넣는다 (판매자 20, 구매자 10)
  private long insertReview(long sellerOrderId, String rating, String... tags) {
    Long id = jdbcTemplate.queryForObject("""
      insert into review (seller_order_id, order_id, buyer_id, seller_id, rating, content, created_at, updated_at)
      values (?, (select order_id from seller_order where id = ?), ?, ?, ?, ?, now(), now()) returning id
      """, Long.class, sellerOrderId, sellerOrderId, BUYER_ID, SELLER_ID, new BigDecimal(rating), CONTENT);
    for (String tag : tags) {
      jdbcTemplate.update("insert into review_tag (review_id, tag) values (?, ?)", id, tag);
    }
    return id;
  }

  private ResultActions createFor(long sellerOrderId) throws Exception {
    return create("""
      {"sellerOrderId": %d, "rating": 4.5, "content": "%s", "tags": ["GOOD_PRICE"]}
      """.formatted(sellerOrderId, CONTENT));
  }

  private ResultActions create(String body) throws Exception {
    return mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  // 판매자 20의 판매자 주문. 주문상품 상태와 구매 확정 시각(null이면 확정 전)을 DB에 직접 넣는다
  private long sellerOrder(long buyerId, LocalDateTime confirmedAt, String... itemStatuses) {
    Orders order = new Orders("ORD-RVC-" + System.nanoTime(), buyerId, "구매자", "01012345678", "12345", "서울시",
      null, itemStatuses.length);
    SellerOrder sellerOrder = new SellerOrder(order, SELLER_ID);
    List<OrdersItem> items = new ArrayList<>();
    for (int i = 0; i < itemStatuses.length; i++) {
      items.add(new OrdersItem(sellerOrder, 100L + i, "몬스테라" + i, new BigDecimal("10000")));
    }
    sellerOrder.addItems(items);
    order.addSellerOrders(List.of(sellerOrder));
    ordersRepository.save(order);
    for (int i = 0; i < itemStatuses.length; i++) {
      jdbcTemplate.update("update orders_item set status = ? where id = ?", itemStatuses[i], items.get(i).getId());
    }
    jdbcTemplate.update("update seller_order set confirmed_at = ? where id = ?", confirmedAt, sellerOrder.getId());
    return sellerOrder.getId();
  }
}
