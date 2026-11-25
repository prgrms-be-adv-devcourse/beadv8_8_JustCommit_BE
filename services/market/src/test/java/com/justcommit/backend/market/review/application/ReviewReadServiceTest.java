package com.justcommit.backend.market.review.application;

import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.ReviewableSellerOrder;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.review.domain.Review;
import com.justcommit.backend.market.review.domain.ReviewTag;
import com.justcommit.backend.market.review.infrastructure.ReviewRepository;
import com.justcommit.backend.market.review.infrastructure.ReviewRepository.RatingCountRow;
import com.justcommit.backend.market.review.infrastructure.ReviewRepository.ReviewTagRow;
import com.justcommit.backend.market.review.presentation.ReviewSummaryResponse;
import com.justcommit.backend.market.review.presentation.ReviewSummaryResponse.RatingCount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewReadServiceTest {

  private static final long SELLER_ID = 20L;
  private static final Pageable PAGEABLE = PageRequest.of(0, 20);

  @Mock ReviewRepository reviewRepository;
  @Mock OrderQuery orderQuery;
  @Mock MemberQuery memberQuery;
  @InjectMocks ReviewReadService reviewReadService;

  @Test
  @DisplayName("평균은 소수 첫째 자리 HALF_UP: 4.0점 1건 + 4.5점 1건 = 4.25 → 4.3 (HALF_EVEN이면 4.2)")
  void averageRoundsHalfUp() {
    when(reviewRepository.countByRating(SELLER_ID)).thenReturn(List.of(ratingRow("4.0", 1), ratingRow("4.5", 1)));
    when(reviewRepository.countByTag(SELLER_ID)).thenReturn(List.of());

    ReviewSummaryResponse summary = reviewReadService.summary(SELLER_ID);

    assertThat(summary.reviewCount()).isEqualTo(2);
    assertThat(summary.averageRating()).isEqualByComparingTo("4.3");
  }

  @Test
  @DisplayName("23건 예시: 15 · 4 · 2 · 1 · 1건 → 평균 107.5 / 23 = 4.67… → 4.7, 별점별 개수 합 = 리뷰 수")
  void averageOfTwentyThreeReviews() {
    when(reviewRepository.countByRating(SELLER_ID)).thenReturn(List.of(ratingRow("5.0", 15), ratingRow("4.5", 4),
      ratingRow("4.0", 2), ratingRow("3.5", 1), ratingRow("3.0", 1)));
    when(reviewRepository.countByTag(SELLER_ID)).thenReturn(List.of());

    ReviewSummaryResponse summary = reviewReadService.summary(SELLER_ID);

    assertThat(summary.reviewCount()).isEqualTo(23);
    assertThat(summary.averageRating()).isEqualByComparingTo("4.7");
    assertThat(summary.ratingCounts()).extracting(RatingCount::count)
      .containsExactly(15L, 4L, 2L, 1L, 1L, 0L, 0L, 0L, 0L, 0L);
  }

  @Test
  @DisplayName("0.5 단위 10구간에 맞지 않는 별점이 있으면 정상 응답을 만들지 않는다 (구간 밖 값의 예외 계약 없음)")
  void ratingOutsideBucketsIsNotSilentlyAccepted() {
    when(reviewRepository.countByRating(SELLER_ID)).thenReturn(List.of(ratingRow("5.0", 1), ratingRow("4.3", 1)));

    assertThatThrownBy(() -> reviewReadService.summary(SELLER_ID))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("sellerId=20")
      .hasMessageContaining("4.3");
  }

  @Test
  @DisplayName("목록이 비면 태그 · 상품명 · 닉네임을 조회하지 않는다")
  void emptyPageSkipsLookups() {
    when(reviewRepository.findBySellerIdAndDeletedAtIsNull(SELLER_ID, PAGEABLE))
      .thenReturn(new PageImpl<>(List.of(), PAGEABLE, 0));

    assertThat(reviewReadService.sellerReviews(SELLER_ID, PAGEABLE).content()).isEmpty();

    verify(reviewRepository, never()).findTagsByReviewIds(any());
    verify(orderQuery, never()).getConfirmedProductNames(any());
    verify(memberQuery, never()).getNicknames(any());
  }

  @Test
  @DisplayName("목록의 태그 · 상품명 · 닉네임은 페이지 전체에 대해 한 번씩, 같은 작성자는 한 번만 묶어서 조회한다")
  void lookupsAreBatchedOncePerPage() {
    Review first = review(1L, 101L, 10L);
    Review second = review(2L, 102L, 10L);
    Review third = review(3L, 103L, 11L);
    when(reviewRepository.findBySellerIdAndDeletedAtIsNull(SELLER_ID, PAGEABLE))
      .thenReturn(new PageImpl<>(List.of(first, second, third), PAGEABLE, 3));
    when(reviewRepository.findTagsByReviewIds(List.of(1L, 2L, 3L))).thenReturn(List.of(tagRow(1L, ReviewTag.GOOD_PRICE)));
    when(orderQuery.getConfirmedProductNames(List.of(101L, 102L, 103L))).thenReturn(Map.of(101L, List.of("몬스테라")));
    when(memberQuery.getNicknames(List.of(10L, 11L))).thenReturn(Map.of(10L, "초록손", 11L, "탈퇴한 회원"));

    var page = reviewReadService.sellerReviews(SELLER_ID, PAGEABLE);

    assertThat(page.content()).hasSize(3);
    assertThat(page.content().get(0).tags()).containsExactly(ReviewTag.GOOD_PRICE);
    assertThat(page.content().get(0).productNames()).containsExactly("몬스테라");
    assertThat(page.content().get(2).writerNickname()).isEqualTo("탈퇴한 회원");
    verify(reviewRepository).findTagsByReviewIds(List.of(1L, 2L, 3L));
    verify(orderQuery).getConfirmedProductNames(List.of(101L, 102L, 103L));
    verify(memberQuery).getNicknames(List.of(10L, 11L));
  }

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 12, 0);

  @Test
  @DisplayName("작성 가능 목록 (#585): 후보 조회 기준 시각은 now - WRITE_PERIOD, 정확히 72시간인 후보와 리뷰가 있는 후보는 빠지고 마감 · id 순으로 정렬한다")
  void writableFiltersAndSorts() {
    ReviewableSellerOrder later = candidate(30L, NOW.minusHours(1));
    ReviewableSellerOrder sameDeadlineSmallerId = candidate(20L, NOW.minusHours(1));
    ReviewableSellerOrder earliest = candidate(40L, NOW.minusHours(70));
    ReviewableSellerOrder exactlyExpired = candidate(50L, NOW.minus(Review.WRITE_PERIOD));
    ReviewableSellerOrder reviewed = candidate(60L, NOW.minusHours(2));
    when(orderQuery.findReviewableSellerOrders(10L, NOW.minus(Review.WRITE_PERIOD)))
      .thenReturn(List.of(later, sameDeadlineSmallerId, earliest, exactlyExpired, reviewed));
    when(reviewRepository.findSellerOrderIdsIn(List.of(30L, 20L, 40L, 60L))).thenReturn(List.of(60L));
    when(orderQuery.getConfirmedProductNames(List.of(40L, 20L, 30L))).thenReturn(Map.of());
    when(memberQuery.getNicknames(List.of(SELLER_ID))).thenReturn(Map.of(SELLER_ID, "베란다정원"));

    var writable = reviewReadService.writableSellerOrders(10L, NOW);

    assertThat(writable).extracting(response -> response.sellerOrderId()).containsExactly(40L, 20L, 30L);
    assertThat(writable.get(0).writableUntil()).isEqualTo(NOW.plusHours(2));
    assertThat(writable.get(0).productNames()).isEmpty();
  }

  @Test
  @DisplayName("작성 가능 목록: 후보가 없거나 모두 리뷰를 썼으면 빈 배열이고 상품명 · 닉네임을 조회하지 않는다")
  void writableEmptySkipsLookups() {
    when(orderQuery.findReviewableSellerOrders(10L, NOW.minus(Review.WRITE_PERIOD))).thenReturn(List.of());
    assertThat(reviewReadService.writableSellerOrders(10L, NOW)).isEmpty();

    when(orderQuery.findReviewableSellerOrders(11L, NOW.minus(Review.WRITE_PERIOD)))
      .thenReturn(List.of(candidate(30L, NOW.minusHours(1))));
    when(reviewRepository.findSellerOrderIdsIn(List.of(30L))).thenReturn(List.of(30L));
    assertThat(reviewReadService.writableSellerOrders(11L, NOW)).isEmpty();

    verify(orderQuery, never()).getConfirmedProductNames(any());
    verify(memberQuery, never()).getNicknames(any());
  }

  private static ReviewableSellerOrder candidate(long sellerOrderId, LocalDateTime confirmedAt) {
    return new ReviewableSellerOrder(sellerOrderId, sellerOrderId + 1000, SELLER_ID, confirmedAt.minusDays(3), confirmedAt);
  }

  private static Review review(long id, long sellerOrderId, long buyerId) {
    Review review = new Review(sellerOrderId, 1L, buyerId, SELLER_ID, new BigDecimal("4.5"), "잎이 싱싱하고 포장이 꼼꼼했어요",
      List.of());
    ReflectionTestUtils.setField(review, "id", id);
    return review;
  }

  private static RatingCountRow ratingRow(String rating, long count) {
    return new RatingCountRow() {
      @Override
      public BigDecimal getRating() {
        return new BigDecimal(rating);
      }

      @Override
      public long getCount() {
        return count;
      }
    };
  }

  private static ReviewTagRow tagRow(long reviewId, ReviewTag tag) {
    return new ReviewTagRow() {
      @Override
      public Long getReviewId() {
        return reviewId;
      }

      @Override
      public ReviewTag getTag() {
        return tag;
      }
    };
  }
}
