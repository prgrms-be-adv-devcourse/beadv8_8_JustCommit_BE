package com.justcommit.backend.market.review.application;

import com.justcommit.backend.common.exception.BusinessException;
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
import com.justcommit.backend.market.review.domain.Review;
import com.justcommit.backend.market.review.domain.ReviewErrorCode;
import com.justcommit.backend.market.review.domain.ReviewTag;
import com.justcommit.backend.market.review.infrastructure.ReviewRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

// 리뷰 작성을 실제 Postgres와 실제 주문 조회(OrderReadService)로 확인한다 (#582).
// 주문 정보를 지연 로딩으로 읽는 조회가 리뷰 작성 트랜잭션 안에서 동작하는지, 동시 작성이 유니크 제약으로 1건만 남는지 본다
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, OrderReadService.class, ReviewService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewServiceIntegrationTest {

  private static final long BUYER_ID = 10L;
  private static final long SELLER_ID = 20L;
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 10, 0);
  private static final BigDecimal RATING = new BigDecimal("4.5");
  private static final String CONTENT = "잎이 싱싱하고 포장이 꼼꼼했어요";
  private static final long TIMEOUT_SECONDS = 10;

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
  @Autowired OrdersRepository ordersRepository;
  @Autowired JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.execute("truncate table review_tag, review, return_request, orders_item, seller_order, orders");
  }

  @Test
  @DisplayName("실제 주문 조회로 본인의 구매 확정된 판매자 주문을 확인하고, 리뷰와 태그를 한 트랜잭션에서 저장한다")
  void createWithRealOrderQuery() {
    Target target = sellerOrder("CONFIRMED", "CANCELLED");
    List<String> transactionNames = Collections.synchronizedList(new ArrayList<>());
    List<Boolean> readOnlyFlags = Collections.synchronizedList(new ArrayList<>());
    Answer<?> real = realAnswer();
    doAnswer(invocation -> {
      transactionNames.add(TransactionSynchronizationManager.getCurrentTransactionName());
      readOnlyFlags.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
      return real.answer(invocation);
    }).when(reviewRepository).existsBySellerOrderId(target.sellerOrderId());

    Long reviewId = reviewService.create(BUYER_ID, target.sellerOrderId(), RATING, "  " + CONTENT + "  ",
      List.of(ReviewTag.CAREFUL_PACKING, ReviewTag.HEALTHY_ARRIVAL), NOW);

    // 중복 확인이 리뷰 작성 트랜잭션(읽기 · 쓰기) 안에서 실행됐다
    assertThat(transactionNames).singleElement().asString().endsWith("ReviewService.create");
    assertThat(readOnlyFlags).containsExactly(false);
    assertThat(jdbcTemplate.queryForMap(
      "select seller_order_id, order_id, buyer_id, seller_id, content from review where id = ?", reviewId))
      .containsEntry("seller_order_id", target.sellerOrderId())
      .containsEntry("order_id", target.orderId())
      .containsEntry("buyer_id", BUYER_ID)
      .containsEntry("seller_id", SELLER_ID)
      .containsEntry("content", CONTENT);
    assertThat(tagsOf(reviewId)).containsExactly("CAREFUL_PACKING", "HEALTHY_ARRIVAL");
  }

  @Test
  @DisplayName("실제 주문 조회: 전부 반품된 판매자 주문은 구매 확정 시각이 있어도 409 REVIEW_NO_CONFIRMED_ITEM")
  void allReturnedSellerOrderIsRejected() {
    Target target = sellerOrder("RETURNED", "RETURNED");

    assertRejected(target, ReviewErrorCode.REVIEW_NO_CONFIRMED_ITEM);
  }

  @Test
  @DisplayName("실제 주문 조회: 남의 판매자 주문은 404 REVIEW_SELLER_ORDER_NOT_FOUND")
  void anotherBuyersSellerOrderIsNotFound() {
    Target target = sellerOrder("CONFIRMED");

    assertThatThrownBy(() -> reviewService.create(99L, target.sellerOrderId(), RATING, CONTENT, List.of(), NOW))
      .isInstanceOfSatisfying(BusinessException.class,
        e -> assertThat(e.getErrorCode()).isEqualTo(ReviewErrorCode.REVIEW_SELLER_ORDER_NOT_FOUND));
  }

  @Test
  @DisplayName("삭제한 리뷰가 있는 판매자 주문에는 다시 쓸 수 없다 (409 REVIEW_ALREADY_WRITTEN)")
  void deletedReviewBlocksRewrite() {
    Target target = sellerOrder("CONFIRMED");
    Long reviewId = reviewService.create(BUYER_ID, target.sellerOrderId(), RATING, CONTENT, List.of(), NOW);
    jdbcTemplate.update("update review set deleted_at = ? where id = ?", NOW, reviewId);

    assertRejected(target, ReviewErrorCode.REVIEW_ALREADY_WRITTEN);
    assertThat(jdbcTemplate.queryForObject("select count(*) from review", Long.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("두 요청이 중복 확인을 모두 통과해도 유니크 제약으로 1건만 저장되고, 나머지는 409 REVIEW_ALREADY_WRITTEN")
  void concurrentCreateIsBlockedByUniqueConstraint() throws Exception {
    Target target = sellerOrder("CONFIRMED");
    List<Boolean> prechecks = Collections.synchronizedList(new ArrayList<>());
    List<DataIntegrityViolationException> violations = Collections.synchronizedList(new ArrayList<>());
    CyclicBarrier afterPrecheck = new CyclicBarrier(2);
    Answer<?> real = realAnswer();
    // 두 요청 모두 중복 확인을 끝낸 뒤에 저장하게 해, 중복 확인이 아니라 유니크 제약에서 막히는 경로를 만든다
    doAnswer(invocation -> {
      Boolean exists = (Boolean) real.answer(invocation);
      prechecks.add(exists);
      afterPrecheck.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return exists;
    }).when(reviewRepository).existsBySellerOrderId(target.sellerOrderId());
    doAnswer(invocation -> {
      try {
        return real.answer(invocation);
      } catch (DataIntegrityViolationException e) {
        violations.add(e);
        throw e;
      }
    }).when(reviewRepository).saveAndFlush(any(Review.class));

    List<ReviewTag> firstTags = List.of(ReviewTag.GOOD_PRICE);
    List<ReviewTag> secondTags = List.of(ReviewTag.CAREFUL_PACKING, ReviewTag.HEALTHY_ARRIVAL);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    List<Object> results = new ArrayList<>();
    try {
      Future<Object> first = executor.submit(createCapturing(target, firstTags));
      Future<Object> second = executor.submit(createCapturing(target, secondTags));
      results.add(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      results.add(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    // 두 요청 모두 중복 확인을 「없음」으로 통과했다
    assertThat(prechecks).containsExactly(false, false);
    // 성공 1건, 중복 작성 409 1건
    assertThat(results).filteredOn(Long.class::isInstance).hasSize(1);
    assertThat(results).filteredOn(BusinessException.class::isInstance).singleElement()
      .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
        .isEqualTo(ReviewErrorCode.REVIEW_ALREADY_WRITTEN));
    // 실패한 쪽은 유니크 제약 위반이었고, 원인에 Hibernate 제약 위반이 바로 들어 있다
    assertThat(violations).singleElement().satisfies(e -> {
      assertThat(e.getCause()).isInstanceOf(ConstraintViolationException.class);
      assertThat(((ConstraintViolationException) e.getCause()).getConstraintName())
        .isEqualTo(Review.UK_SELLER_ORDER_ID);
    });
    // DB에는 리뷰 1건과 성공한 리뷰의 태그만 남는다
    boolean firstWon = results.get(0) instanceof Long;
    Long savedId = (Long) results.get(firstWon ? 0 : 1);
    List<ReviewTag> winnerTags = firstWon ? firstTags : secondTags;
    assertThat(jdbcTemplate.queryForList("select id from review", Long.class)).containsExactly(savedId);
    assertThat(jdbcTemplate.queryForList("select tag from review_tag order by tag", String.class))
      .containsExactlyElementsOf(winnerTags.stream().map(Enum::name).sorted().toList());
  }

  @Test
  @DisplayName("같은 리뷰를 동시에 지우면 한 요청만 200이고 다른 요청은 404 REVIEW_NOT_FOUND, 먼저 저장한 deleted_at은 덮어써지지 않는다 (#584)")
  void concurrentDeleteSucceedsOnce() throws Exception {
    Target target = sellerOrder("CONFIRMED");
    // 리뷰 작성 시각은 실제 시각(감사 기능)이라 삭제 시각도 실제 시각 기준으로 둔다 (삭제 기간 안)
    Long reviewId = reviewService.create(BUYER_ID, target.sellerOrderId(), RATING, CONTENT, List.of(), NOW);
    LocalDateTime firstNow = LocalDateTime.now().withNano(0);
    LocalDateTime secondNow = firstNow.plusSeconds(1);
    CyclicBarrier beforeUpdate = new CyclicBarrier(2);
    Answer<?> real = realAnswer();
    // 두 요청이 모두 소유 · 미삭제 확인을 통과한 뒤 UPDATE하게 해, 확인이 아니라 조건부 UPDATE에서 갈리는 경로를 만든다
    doAnswer(invocation -> {
      beforeUpdate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return real.answer(invocation);
    }).when(reviewRepository).softDelete(any(), any(), any());

    ExecutorService executor = Executors.newFixedThreadPool(2);
    Object firstResult;
    Object secondResult;
    try {
      Future<Object> first = executor.submit(deleteCapturing(reviewId, firstNow));
      Future<Object> second = executor.submit(deleteCapturing(reviewId, secondNow));
      firstResult = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      secondResult = second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    // 한 건은 성공(null), 다른 한 건은 404
    List<Object> results = Arrays.asList(firstResult, secondResult);
    assertThat(results).filteredOn(Objects::isNull).hasSize(1);
    assertThat(results).filteredOn(BusinessException.class::isInstance).singleElement()
      .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ReviewErrorCode.REVIEW_NOT_FOUND));
    // deleted_at은 성공한 요청의 시각 그대로 (진 요청이 덮어쓰지 않음)
    LocalDateTime winnerNow = firstResult == null ? firstNow : secondNow;
    assertThat(jdbcTemplate.queryForObject("select deleted_at from review where id = ?", LocalDateTime.class, reviewId))
      .isEqualTo(winnerNow);
  }

  private Callable<Object> deleteCapturing(Long reviewId, LocalDateTime now) {
    return () -> {
      try {
        reviewService.delete(BUYER_ID, reviewId, now);
        return null;
      } catch (BusinessException e) {
        return e;
      }
    };
  }

  private Callable<Object> createCapturing(Target target, List<ReviewTag> tags) {
    return () -> {
      try {
        return reviewService.create(BUYER_ID, target.sellerOrderId(), RATING, CONTENT, tags, NOW);
      } catch (BusinessException e) {
        return e;
      }
    };
  }

  private void assertRejected(Target target, ReviewErrorCode errorCode) {
    assertThatThrownBy(() -> reviewService.create(BUYER_ID, target.sellerOrderId(), RATING, CONTENT, List.of(), NOW))
      .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
  }

  // 스파이의 기본 동작(실제 저장소 호출)
  private Answer<?> realAnswer() {
    return mockingDetails(reviewRepository).getMockCreationSettings().getDefaultAnswer();
  }

  private List<String> tagsOf(Long reviewId) {
    return jdbcTemplate.queryForList("select tag from review_tag where review_id = ? order by tag", String.class,
      reviewId);
  }

  // 구매자 10 · 판매자 20의 판매자 주문. 주문상품 상태를 바꾸고 구매 확정 시각을 NOW 하루 전으로 둔다
  private Target sellerOrder(String... itemStatuses) {
    Orders order = new Orders("ORD-RV-" + System.nanoTime(), BUYER_ID, "구매자", "01012345678", "12345", "서울시",
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
    jdbcTemplate.update("update seller_order set confirmed_at = ? where id = ?", NOW.minusDays(1),
      sellerOrder.getId());
    return new Target(order.getId(), sellerOrder.getId());
  }

  private record Target(Long orderId, Long sellerOrderId) {
  }
}
