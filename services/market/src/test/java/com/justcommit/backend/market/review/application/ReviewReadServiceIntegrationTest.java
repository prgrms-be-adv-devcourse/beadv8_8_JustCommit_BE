package com.justcommit.backend.market.review.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.client.product.ProductQuery;
import com.justcommit.backend.market.client.settlement.SettlementQuery;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.member.application.SellerAddressReader;
import com.justcommit.backend.market.order.application.OrderReadService;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.review.domain.Review;
import com.justcommit.backend.market.review.domain.ReviewErrorCode;
import com.justcommit.backend.market.review.domain.ReviewTag;
import com.justcommit.backend.market.review.presentation.MyReviewListRequest;
import com.justcommit.backend.market.review.presentation.MyReviewResponse;
import com.justcommit.backend.market.review.presentation.ReviewSummaryResponse;
import com.justcommit.backend.market.review.presentation.ReviewSummaryResponse.RatingCount;
import com.justcommit.backend.market.review.presentation.ReviewSummaryResponse.TagCount;
import com.justcommit.backend.market.review.presentation.SellerReviewListRequest;
import com.justcommit.backend.market.review.presentation.SellerReviewResponse;
import com.justcommit.backend.market.review.presentation.WritableReviewResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 판매자 리뷰 목록 · 집계를 실제 Postgres, 실제 주문 조회(OrderReadService), 실제 닉네임 조회(회원 복제본)로 확인한다 (#583)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, OrderReadService.class, ReviewReadService.class, ReviewService.class,
  ReviewReadServiceIntegrationTest.MemberClientScan.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewReadServiceIntegrationTest {

  private static final long SELLER_ID = 20L;
  private static final long OTHER_SELLER_ID = 21L;
  private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 20, 10, 0);
  private static final List<BigDecimal> TEN_RATINGS = List.of("5.0", "4.5", "4.0", "3.5", "3.0", "2.5", "2.0", "1.5",
    "1.0", "0.5").stream().map(BigDecimal::new).toList();

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
  }

  // 닉네임 조회 구현(ReplicaMemberClient)은 패키지 밖에서 보이지 않아 그 패키지를 스캔한다
  @TestConfiguration
  @ComponentScan(basePackageClasses = MemberQuery.class)
  static class MemberClientScan {
  }

  // ReplicaMemberClient의 배송지 조회 · OrderReadService의 다른 조회가 쓰는 의존성. 이 테스트에서는 쓰지 않는다
  @MockitoBean SellerAddressReader sellerAddressReader;
  @MockitoBean ShipmentQuery shipmentQuery;
  @MockitoBean SettlementQuery settlementQuery;
  @MockitoBean ProductQuery productQuery;

  @Autowired ReviewReadService reviewReadService;
  @Autowired ReviewService reviewService;
  @Autowired OrdersRepository ordersRepository;
  @Autowired JdbcTemplate jdbcTemplate;

  private final AtomicLong sellerOrderIds = new AtomicLong(10_000);

  @AfterEach
  void cleanUp() {
    jdbcTemplate.execute("truncate table review_tag, review, member_replica, return_request, orders_item, "
      + "seller_order, orders");
  }

  @Test
  @DisplayName("목록: 삭제하지 않은 리뷰만 최신순(같은 시각이면 나중에 저장한 것 먼저)으로, 태그 · 확정 상품명 · 작성자 닉네임과 함께")
  void sellerReviewsWithTagsProductNamesAndNicknames() {
    replica(10L, "초록손", false);
    replica(11L, null, true);
    long sellerOrderId = sellerOrder(10L, SELLER_ID, new String[] {"몬스테라", "CONFIRMED"}, new String[] {"선인장", "CANCELLED"},
      new String[] {"고무나무", "CONFIRMED"}, new String[] {"필레아", "RETURNED"});
    long first = review(sellerOrderId, 10L, SELLER_ID, "5.0", BASE, null, ReviewTag.HEALTHY_ARRIVAL, ReviewTag.AS_DESCRIBED);
    long withdrawn = review(nextSellerOrderId(), 11L, SELLER_ID, "4.5", BASE.plusHours(1), null);
    long unknown = review(nextSellerOrderId(), 12L, SELLER_ID, "4.0", BASE.plusHours(1), null, ReviewTag.GOOD_PRICE);
    review(nextSellerOrderId(), 10L, SELLER_ID, "1.0", BASE.plusHours(2), BASE.plusHours(3)); // 삭제함
    review(nextSellerOrderId(), 10L, OTHER_SELLER_ID, "3.0", BASE.plusHours(4), null); // 다른 판매자

    PagingResponse<SellerReviewResponse> page = reviewReadService.sellerReviews(SELLER_ID,
      new SellerReviewListRequest(null, null).toPageable());

    assertThat(page.totalElements()).isEqualTo(3);
    assertThat(page.content()).extracting(SellerReviewResponse::reviewId).containsExactly(unknown, withdrawn, first);
    SellerReviewResponse firstReview = page.content().get(2);
    assertThat(firstReview.rating()).isEqualByComparingTo("5.0");
    assertThat(firstReview.tags()).containsExactly(ReviewTag.AS_DESCRIBED, ReviewTag.HEALTHY_ARRIVAL); // enum 순서
    assertThat(firstReview.productNames()).containsExactly("몬스테라", "고무나무"); // 취소 · 반품 상품은 빠짐
    assertThat(firstReview.writerNickname()).isEqualTo("초록손");
    assertThat(firstReview.createdAt()).isEqualTo(BASE);
    assertThat(page.content().get(1).writerNickname()).isEqualTo("탈퇴한 회원");
    assertThat(page.content().get(1).tags()).isEmpty();
    assertThat(page.content().get(1).productNames()).isEmpty(); // 주문 정보가 없는 판매자 주문
    assertThat(page.content().get(0).writerNickname()).isEqualTo("알 수 없는 회원");
  }

  @Test
  @DisplayName("목록: 페이지 크기만큼 나누고 전체 수 · 페이지 수를 돌려준다")
  void sellerReviewsArePaged() {
    for (int i = 0; i < 3; i++) {
      review(nextSellerOrderId(), 10L, SELLER_ID, "5.0", BASE.plusMinutes(i), null);
    }

    PagingResponse<SellerReviewResponse> page = reviewReadService.sellerReviews(SELLER_ID,
      new SellerReviewListRequest(1, 2).toPageable());

    assertThat(page.content()).hasSize(1);
    assertThat(page.page()).isEqualTo(1);
    assertThat(page.size()).isEqualTo(2);
    assertThat(page.totalElements()).isEqualTo(3);
    assertThat(page.totalPages()).isEqualTo(2);
  }

  @Test
  @DisplayName("리뷰가 없는 판매자: 빈 목록, 집계는 리뷰 수 0 · 평균 null · 별점 10구간과 태그 5종 모두 0")
  void sellerWithoutReviews() {
    review(nextSellerOrderId(), 10L, SELLER_ID, "5.0", BASE, BASE.plusHours(1), ReviewTag.GOOD_PRICE); // 삭제한 리뷰뿐

    assertThat(reviewReadService.sellerReviews(SELLER_ID, new SellerReviewListRequest(null, null).toPageable()))
      .satisfies(page -> {
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
      });
    ReviewSummaryResponse summary = reviewReadService.summary(SELLER_ID);
    assertThat(summary.reviewCount()).isZero();
    assertThat(summary.averageRating()).isNull();
    assertRatingCounts(summary, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    assertThat(summary.tagCounts()).extracting(TagCount::tag).containsExactly(ReviewTag.values());
    assertThat(summary.tagCounts()).extracting(TagCount::count).containsOnly(0L);
  }

  @Test
  @DisplayName("집계 23건 예시: 5.0점 15 · 4.5점 4 · 4.0점 2 · 3.5점 1 · 3.0점 1 → 리뷰 수 23, 평균 4.7. 삭제한 리뷰 · 다른 판매자는 빠진다")
  void summaryOfTwentyThreeReviews() {
    for (int i = 0; i < 15; i++) {
      // 앞의 5건은 태그 3개: 평균 · 전체 수 · 별점별 개수에는 한 번만 들어가야 한다
      ReviewTag[] tags = i < 5
        ? new ReviewTag[] {ReviewTag.CAREFUL_PACKING, ReviewTag.HEALTHY_ARRIVAL, ReviewTag.AS_DESCRIBED}
        : new ReviewTag[] {ReviewTag.CAREFUL_PACKING};
      review(nextSellerOrderId(), 10L, SELLER_ID, "5.0", BASE.plusMinutes(i), null, tags);
    }
    for (int i = 0; i < 4; i++) {
      review(nextSellerOrderId(), 10L, SELLER_ID, "4.5", BASE.plusMinutes(20 + i), null, ReviewTag.HEALTHY_ARRIVAL);
    }
    review(nextSellerOrderId(), 10L, SELLER_ID, "4.0", BASE.plusMinutes(30), null);
    review(nextSellerOrderId(), 10L, SELLER_ID, "4.0", BASE.plusMinutes(31), null, ReviewTag.GOOD_PRICE);
    review(nextSellerOrderId(), 10L, SELLER_ID, "3.5", BASE.plusMinutes(32), null);
    review(nextSellerOrderId(), 10L, SELLER_ID, "3.0", BASE.plusMinutes(33), null);
    // 빠져야 하는 것: 삭제한 리뷰(별점 0.5 · 태그 있음), 다른 판매자의 리뷰
    for (int i = 0; i < 3; i++) {
      review(nextSellerOrderId(), 10L, SELLER_ID, "0.5", BASE.plusMinutes(40 + i), BASE.plusHours(5),
        ReviewTag.DETAILED_DESCRIPTION, ReviewTag.GOOD_PRICE);
    }
    review(nextSellerOrderId(), 10L, OTHER_SELLER_ID, "1.0", BASE, null, ReviewTag.DETAILED_DESCRIPTION);

    ReviewSummaryResponse summary = reviewReadService.summary(SELLER_ID);

    assertThat(summary.reviewCount()).isEqualTo(23);
    assertThat(summary.averageRating()).isEqualByComparingTo("4.7"); // 107.5 / 23 = 4.67... → HALF_UP
    assertThat(summary.averageRating().scale()).isEqualTo(1);
    assertRatingCounts(summary, 15, 4, 2, 1, 1, 0, 0, 0, 0, 0);
    assertThat(summary.ratingCounts().stream().mapToLong(RatingCount::count).sum()).isEqualTo(summary.reviewCount());
    assertThat(summary.tagCounts()).extracting(TagCount::tag).containsExactly(ReviewTag.values());
    assertThat(summary.tagCounts()).extracting(TagCount::count)
      .containsExactly(5L, 0L, 15L, 9L, 1L); // AS_DESCRIBED · DETAILED_DESCRIPTION · CAREFUL_PACKING · HEALTHY_ARRIVAL · GOOD_PRICE
  }

  @Test
  @DisplayName("집계: 태그가 3개인 리뷰 1건은 리뷰 수 1 · 평균은 그 별점 · 별점 구간 1, 태그마다 1")
  void multiTagReviewCountsOnce() {
    review(nextSellerOrderId(), 10L, SELLER_ID, "3.5", BASE, null,
      ReviewTag.AS_DESCRIBED, ReviewTag.CAREFUL_PACKING, ReviewTag.GOOD_PRICE);

    ReviewSummaryResponse summary = reviewReadService.summary(SELLER_ID);

    assertThat(summary.reviewCount()).isEqualTo(1);
    assertThat(summary.averageRating()).isEqualByComparingTo("3.5");
    assertRatingCounts(summary, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0);
    assertThat(summary.tagCounts()).extracting(TagCount::count).containsExactly(1L, 0L, 1L, 0L, 1L);
  }

  @Test
  @DisplayName("내 리뷰 목록 (#584): 내가 쓴 미삭제 리뷰만 최신순, 판매자 닉네임 · 주문일 · 확정 상품명 · 태그 · deletableUntil(작성 + 72시간)")
  void myReviews() {
    replica(SELLER_ID, "베란다정원", false);
    replica(OTHER_SELLER_ID, null, true);
    long sellerOrderId = sellerOrder(10L, SELLER_ID, new String[] {"몬스테라", "CONFIRMED"}, new String[] {"선인장", "CANCELLED"});
    long orderId = jdbcTemplate.queryForObject("select order_id from seller_order where id = ?", Long.class, sellerOrderId);
    LocalDateTime orderedAt = jdbcTemplate.queryForObject("select created_at from orders where id = ?", LocalDateTime.class,
      orderId);
    long older = review(sellerOrderId, 10L, SELLER_ID, "4.5", BASE, null, ReviewTag.HEALTHY_ARRIVAL, ReviewTag.CAREFUL_PACKING);
    jdbcTemplate.update("update review set order_id = ? where id = ?", orderId, older);
    long newer = review(nextSellerOrderId(), 10L, OTHER_SELLER_ID, "4.0", BASE.plusHours(1), null);
    review(nextSellerOrderId(), 10L, SELLER_ID, "5.0", BASE.plusHours(2), BASE.plusHours(3)); // 삭제함
    review(nextSellerOrderId(), 99L, SELLER_ID, "3.0", BASE.plusHours(4), null); // 다른 회원의 리뷰

    PagingResponse<MyReviewResponse> page = reviewReadService.myReviews(10L, new MyReviewListRequest(null, null).toPageable());

    assertThat(page.totalElements()).isEqualTo(2);
    assertThat(page.content()).extracting(MyReviewResponse::reviewId).containsExactly(newer, older);
    MyReviewResponse olderReview = page.content().get(1);
    assertThat(olderReview.sellerId()).isEqualTo(SELLER_ID);
    assertThat(olderReview.sellerNickname()).isEqualTo("베란다정원");
    assertThat(olderReview.orderId()).isEqualTo(orderId);
    assertThat(olderReview.orderedAt()).isEqualTo(orderedAt);
    assertThat(olderReview.rating()).isEqualByComparingTo("4.5");
    assertThat(olderReview.tags()).containsExactly(ReviewTag.CAREFUL_PACKING, ReviewTag.HEALTHY_ARRIVAL);
    assertThat(olderReview.productNames()).containsExactly("몬스테라"); // 취소 상품은 빠짐
    assertThat(olderReview.createdAt()).isEqualTo(BASE);
    assertThat(olderReview.deletableUntil()).isEqualTo(BASE.plusHours(72));
    // 삭제 기한이 지난 리뷰도 목록에는 남는다
    assertThat(olderReview.deletableUntil()).isBefore(LocalDateTime.now());
    assertThat(page.content().get(0).sellerNickname()).isEqualTo("탈퇴한 회원");
    assertThat(page.content().get(0).orderedAt()).isNull(); // 주문 정보가 없는 리뷰
  }

  @Test
  @DisplayName("내 리뷰 목록: 쓴 리뷰가 없거나 모두 삭제했으면 빈 목록")
  void myReviewsEmpty() {
    review(nextSellerOrderId(), 10L, SELLER_ID, "5.0", BASE, BASE.plusHours(1));

    assertThat(reviewReadService.myReviews(10L, new MyReviewListRequest(null, null).toPageable()).content()).isEmpty();
  }

  private static final LocalDateTime WRITABLE_NOW = LocalDateTime.of(2026, 11, 24, 12, 0);

  @Test
  @DisplayName("작성 가능 목록 (#585): 본인 · 확정 · 확정 상품 있음 · 72시간 안 · 리뷰 없음(삭제 포함)만, 마감이 가까운 순 · 같으면 판매자 주문 id 순")
  void writableSellerOrders() {
    replica(SELLER_ID, "베란다정원", false);
    replica(OTHER_SELLER_ID, null, true);
    long partial = confirmed(sellerOrder(10L, SELLER_ID, new String[] {"몬스테라", "CONFIRMED"},
      new String[] {"선인장", "CANCELLED"}), WRITABLE_NOW.minusHours(1));
    long sameDeadline = confirmed(sellerOrder(10L, SELLER_ID, new String[] {"고무나무", "CONFIRMED"}),
      WRITABLE_NOW.minusHours(1));
    long almostExpired = confirmed(sellerOrder(10L, OTHER_SELLER_ID, new String[] {"필레아", "CONFIRMED"}),
      WRITABLE_NOW.minusHours(72).plusSeconds(1));
    // 빠져야 하는 것
    confirmed(sellerOrder(99L, SELLER_ID, new String[] {"남의 주문", "CONFIRMED"}), WRITABLE_NOW.minusHours(1));
    sellerOrder(10L, SELLER_ID, new String[] {"확정 전", "NORMAL"});
    confirmed(sellerOrder(10L, SELLER_ID, new String[] {"반품1", "RETURNED"}, new String[] {"반품2", "RETURNED"}),
      WRITABLE_NOW.minusHours(1));
    confirmed(sellerOrder(10L, SELLER_ID, new String[] {"정확히 72시간", "CONFIRMED"}), WRITABLE_NOW.minusHours(72));
    long reviewed = confirmed(sellerOrder(10L, SELLER_ID, new String[] {"리뷰 씀", "CONFIRMED"}), WRITABLE_NOW.minusHours(2));
    review(reviewed, 10L, SELLER_ID, "5.0", WRITABLE_NOW.minusHours(1), null);
    long deleted = confirmed(sellerOrder(10L, SELLER_ID, new String[] {"리뷰 삭제함", "CONFIRMED"}), WRITABLE_NOW.minusHours(3));
    review(deleted, 10L, SELLER_ID, "5.0", WRITABLE_NOW.minusHours(2), WRITABLE_NOW.minusHours(1));

    List<WritableReviewResponse> writable = reviewReadService.writableSellerOrders(10L, WRITABLE_NOW);

    assertThat(writable).extracting(WritableReviewResponse::sellerOrderId)
      .containsExactly(almostExpired, partial, sameDeadline);
    WritableReviewResponse first = writable.get(0);
    assertThat(first.sellerId()).isEqualTo(OTHER_SELLER_ID);
    assertThat(first.sellerNickname()).isEqualTo("탈퇴한 회원");
    assertThat(first.confirmedAt()).isEqualTo(WRITABLE_NOW.minusHours(72).plusSeconds(1));
    assertThat(first.writableUntil()).isEqualTo(WRITABLE_NOW.plusSeconds(1));
    WritableReviewResponse partialOrder = writable.get(1);
    assertThat(partialOrder.sellerNickname()).isEqualTo("베란다정원");
    assertThat(partialOrder.productNames()).containsExactly("몬스테라"); // 취소 상품은 빠짐
    assertThat(partialOrder.orderId()).isEqualTo(orderIdOf(partial));
    assertThat(partialOrder.orderedAt()).isEqualTo(
      jdbcTemplate.queryForObject("select created_at from orders where id = ?", LocalDateTime.class, orderIdOf(partial)));
    assertThat(partialOrder.writableUntil()).isEqualTo(WRITABLE_NOW.plusHours(71));
  }

  @Test
  @DisplayName("작성 가능 목록: 쓸 수 있는 주문이 없으면 빈 배열")
  void writableSellerOrdersEmpty() {
    confirmed(sellerOrder(10L, SELLER_ID, new String[] {"정확히 72시간", "CONFIRMED"}), WRITABLE_NOW.minusHours(72));

    assertThat(reviewReadService.writableSellerOrders(10L, WRITABLE_NOW)).isEmpty();
  }

  @Test
  @DisplayName("작성 가능 목록과 작성 API(#582)는 같은 기준 시각에 같은 판단: 목록의 주문은 작성되고, 빠진 주문은 정해진 409로 거절된다")
  void writableListMatchesCreateRules() {
    long writableA = confirmed(sellerOrder(10L, SELLER_ID, new String[] {"몬스테라", "CONFIRMED"}), WRITABLE_NOW.minusHours(1));
    long writableB = confirmed(sellerOrder(10L, SELLER_ID, new String[] {"필레아", "CONFIRMED"}),
      WRITABLE_NOW.minusHours(72).plusNanos(1_000));
    long expired = confirmed(sellerOrder(10L, SELLER_ID, new String[] {"고무나무", "CONFIRMED"}), WRITABLE_NOW.minusHours(72));
    long allReturned = confirmed(sellerOrder(10L, SELLER_ID, new String[] {"반품", "RETURNED"}), WRITABLE_NOW.minusHours(1));

    List<Long> listed = reviewReadService.writableSellerOrders(10L, WRITABLE_NOW).stream()
      .map(WritableReviewResponse::sellerOrderId).toList();

    assertThat(listed).containsExactlyInAnyOrder(writableA, writableB);
    for (Long sellerOrderId : listed) {
      assertThat(reviewService.create(10L, sellerOrderId, new BigDecimal("4.5"), "잎이 싱싱하고 포장이 꼼꼼했어요", List.of(),
        WRITABLE_NOW)).isNotNull();
    }
    assertCreateRejected(expired, ReviewErrorCode.REVIEW_PERIOD_EXPIRED);
    assertCreateRejected(allReturned, ReviewErrorCode.REVIEW_NO_CONFIRMED_ITEM);
    // 리뷰를 쓴 주문은 목록에서 빠진다
    assertThat(reviewReadService.writableSellerOrders(10L, WRITABLE_NOW)).isEmpty();
  }

  @Test
  @DisplayName("작성 가능 목록: 마감까지 DB 저장 정밀도(마이크로초)보다 짧게 남아도 작성할 수 있으면 나오고, 정확히 72시간 · 마감 직후는 빠진다")
  void writableWithSubMicrosecondRemaining() {
    LocalDateTime confirmedAt = LocalDateTime.of(2026, 11, 21, 12, 0, 0, 123_456_000); // 마이크로초 단위로 저장되는 값
    long sellerOrderId = confirmed(sellerOrder(10L, SELLER_ID, new String[] {"몬스테라", "CONFIRMED"}), confirmedAt);
    LocalDateTime deadline = confirmedAt.plus(Review.WRITE_PERIOD);

    // 남은 시간 400ns · 600ns: 조회 기준값(now - 72시간)이 마이크로초로 반올림 · 버림되는 두 경우
    for (long remainingNanos : new long[] {400, 600}) {
      LocalDateTime now = deadline.minusNanos(remainingNanos);
      assertThat(Review.isWithinWritePeriod(confirmedAt, now)).isTrue();
      assertThat(reviewReadService.writableSellerOrders(10L, now))
        .as("마감 %dns 전", remainingNanos)
        .extracting(WritableReviewResponse::sellerOrderId).containsExactly(sellerOrderId);
    }
    assertThat(reviewReadService.writableSellerOrders(10L, deadline)).isEmpty();
    assertThat(reviewReadService.writableSellerOrders(10L, deadline.plusNanos(400))).isEmpty();
    assertThat(reviewReadService.writableSellerOrders(10L, deadline.plusNanos(600))).isEmpty();
    // 목록에 나온 시각에는 작성 API도 통과한다
    assertThat(reviewService.create(10L, sellerOrderId, new BigDecimal("4.5"), "잎이 싱싱하고 포장이 꼼꼼했어요", List.of(),
      deadline.minusNanos(400))).isNotNull();
  }

  private void assertCreateRejected(long sellerOrderId, ReviewErrorCode errorCode) {
    assertThatThrownBy(() -> reviewService.create(10L, sellerOrderId, new BigDecimal("4.5"), "잎이 싱싱하고 포장이 꼼꼼했어요",
      List.of(), WRITABLE_NOW))
      .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
  }

  private long confirmed(long sellerOrderId, LocalDateTime confirmedAt) {
    jdbcTemplate.update("update seller_order set confirmed_at = ? where id = ?", confirmedAt, sellerOrderId);
    return sellerOrderId;
  }

  private long orderIdOf(long sellerOrderId) {
    return jdbcTemplate.queryForObject("select order_id from seller_order where id = ?", Long.class, sellerOrderId);
  }

  private static void assertRatingCounts(ReviewSummaryResponse summary, long... counts) {
    assertThat(summary.ratingCounts()).extracting(RatingCount::rating)
      .usingElementComparator(BigDecimal::compareTo).containsExactlyElementsOf(TEN_RATINGS);
    assertThat(summary.ratingCounts()).extracting(RatingCount::count)
      .containsExactly(Arrays.stream(counts).boxed().toArray(Long[]::new));
  }

  private long nextSellerOrderId() {
    return sellerOrderIds.incrementAndGet();
  }

  // 리뷰를 DB에 직접 넣는다 (작성 규칙은 #582 테스트에서 본다). 작성 시각 · 삭제 시각을 정할 수 있게
  private long review(long sellerOrderId, long buyerId, long sellerId, String rating, LocalDateTime createdAt,
    LocalDateTime deletedAt, ReviewTag... tags) {
    Long id = jdbcTemplate.queryForObject("""
      insert into review (seller_order_id, order_id, buyer_id, seller_id, rating, content, deleted_at, created_at, updated_at)
      values (?, 1, ?, ?, ?, '잎이 싱싱하고 포장이 꼼꼼했어요', ?, ?, ?) returning id
      """, Long.class, sellerOrderId, buyerId, sellerId, new BigDecimal(rating),
      deletedAt == null ? null : Timestamp.valueOf(deletedAt), Timestamp.valueOf(createdAt), Timestamp.valueOf(createdAt));
    for (ReviewTag tag : tags) {
      jdbcTemplate.update("insert into review_tag (review_id, tag) values (?, ?)", id, tag.name());
    }
    return id;
  }

  private void replica(long memberId, String nickname, boolean withdrawn) {
    jdbcTemplate.update("""
      insert into member_replica (member_id, nickname, role, seller, withdrawn, last_event_at, updated_at)
      values (?, ?, 'MEMBER', false, ?, now(), now())
      """, memberId, nickname, withdrawn);
  }

  // 판매자 주문과 주문상품(이름, 상태)을 만든다
  private long sellerOrder(long buyerId, long sellerId, String[]... items) {
    Orders order = new Orders("ORD-RVR-" + System.nanoTime(), buyerId, "구매자", "01012345678", "12345", "서울시",
      null, items.length);
    SellerOrder sellerOrder = new SellerOrder(order, sellerId);
    List<OrdersItem> orderItems = new ArrayList<>();
    for (int i = 0; i < items.length; i++) {
      orderItems.add(new OrdersItem(sellerOrder, 100L + i, items[i][0], new BigDecimal("10000")));
    }
    sellerOrder.addItems(orderItems);
    order.addSellerOrders(List.of(sellerOrder));
    ordersRepository.save(order);
    for (int i = 0; i < items.length; i++) {
      jdbcTemplate.update("update orders_item set status = ? where id = ?", items[i][1], orderItems.get(i).getId());
    }
    return sellerOrder.getId();
  }
}
