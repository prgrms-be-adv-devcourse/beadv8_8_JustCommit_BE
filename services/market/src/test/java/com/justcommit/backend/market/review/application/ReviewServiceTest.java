package com.justcommit.backend.market.review.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.SellerOrderReviewInfo;
import com.justcommit.backend.market.review.domain.Review;
import com.justcommit.backend.market.review.domain.ReviewErrorCode;
import com.justcommit.backend.market.review.domain.ReviewTag;
import com.justcommit.backend.market.review.infrastructure.ReviewRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

  private static final long BUYER_ID = 10L;
  private static final long SELLER_ORDER_ID = 7L;
  private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 11, 24, 10, 0);
  private static final LocalDateTime NOW = CONFIRMED_AT.plusDays(1);
  private static final BigDecimal RATING = new BigDecimal("4.5");
  private static final String CONTENT = "잎이 싱싱하고 포장이 꼼꼼했어요";

  @Mock OrderQuery orderQuery;
  @Mock ReviewRepository reviewRepository;
  @InjectMocks ReviewService reviewService;

  @Test
  @DisplayName("본인의 구매 확정된 판매자 주문이면 주문 · 판매자 번호를 주문 정보에서 복사해 저장한다")
  void createSavesReview() {
    givenInfo(info(BUYER_ID, CONFIRMED_AT, true));
    when(reviewRepository.saveAndFlush(any(Review.class))).thenAnswer(invocation -> {
      Review review = invocation.getArgument(0);
      ReflectionTestUtils.setField(review, "id", 100L);
      return review;
    });

    Long reviewId = create(List.of(ReviewTag.GOOD_PRICE));

    assertThat(reviewId).isEqualTo(100L);
    ArgumentCaptor<Review> saved = ArgumentCaptor.forClass(Review.class);
    verify(reviewRepository).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getSellerOrderId()).isEqualTo(SELLER_ORDER_ID);
    assertThat(saved.getValue().getOrderId()).isEqualTo(9L);
    assertThat(saved.getValue().getBuyerId()).isEqualTo(BUYER_ID);
    assertThat(saved.getValue().getSellerId()).isEqualTo(20L);
    assertThat(saved.getValue().getTags()).containsExactly(ReviewTag.GOOD_PRICE);
  }

  @Test
  @DisplayName("없는 판매자 주문은 404이다")
  void notFoundWhenSellerOrderDoesNotExist() {
    when(orderQuery.findSellerOrderReviewInfo(SELLER_ORDER_ID)).thenReturn(Optional.empty());

    assertRejected(ReviewErrorCode.REVIEW_SELLER_ORDER_NOT_FOUND);
  }

  @Test
  @DisplayName("남의 판매자 주문은 없는 주문과 같은 404이고, 리뷰가 있는지 확인하지 않는다")
  void notFoundWhenSellerOrderBelongsToAnotherBuyer() {
    givenInfo(info(99L, CONFIRMED_AT, true));

    assertRejected(ReviewErrorCode.REVIEW_SELLER_ORDER_NOT_FOUND);
    verify(reviewRepository, never()).existsBySellerOrderId(any());
  }

  @Test
  @DisplayName("구매 확정 전이면 409 REVIEW_NOT_CONFIRMED")
  void conflictWhenNotConfirmed() {
    givenInfo(info(BUYER_ID, null, false));

    assertRejected(ReviewErrorCode.REVIEW_NOT_CONFIRMED);
  }

  @Test
  @DisplayName("전부 반품돼 구매 확정된 주문상품이 없으면 409 REVIEW_NO_CONFIRMED_ITEM")
  void conflictWhenAllItemsReturned() {
    givenInfo(info(BUYER_ID, CONFIRMED_AT, false));

    assertRejected(ReviewErrorCode.REVIEW_NO_CONFIRMED_ITEM);
  }

  @Test
  @DisplayName("이미 쓴(삭제한 리뷰 포함) 판매자 주문이면 409 REVIEW_ALREADY_WRITTEN")
  void conflictWhenAlreadyWritten() {
    givenInfo(info(BUYER_ID, CONFIRMED_AT, true));
    when(reviewRepository.existsBySellerOrderId(SELLER_ORDER_ID)).thenReturn(true);

    assertRejected(ReviewErrorCode.REVIEW_ALREADY_WRITTEN);
  }

  @Test
  @DisplayName("작성 기간이 끝났으면(구매 확정 후 정확히 3일째부터) 409 REVIEW_PERIOD_EXPIRED")
  void conflictWhenWritePeriodExpired() {
    givenInfo(info(BUYER_ID, NOW.minusDays(3), true));

    assertRejected(ReviewErrorCode.REVIEW_PERIOD_EXPIRED);
  }

  @Test
  @DisplayName("동시에 먼저 저장된 리뷰의 판매자 주문 유니크 위반은 409 REVIEW_ALREADY_WRITTEN")
  void uniqueViolationOnSellerOrderBecomesConflict() {
    givenInfo(info(BUYER_ID, CONFIRMED_AT, true));
    when(reviewRepository.saveAndFlush(any(Review.class))).thenThrow(violation(Review.UK_SELLER_ORDER_ID));

    assertThatThrownBy(() -> create(List.of()))
      .isInstanceOfSatisfying(BusinessException.class,
        e -> assertThat(e.getErrorCode()).isEqualTo(ReviewErrorCode.REVIEW_ALREADY_WRITTEN));
  }

  @Test
  @DisplayName("다른 제약 위반은 중복 작성으로 바꾸지 않고 그대로 던진다")
  void otherConstraintViolationIsRethrown() {
    givenInfo(info(BUYER_ID, CONFIRMED_AT, true));
    DataIntegrityViolationException otherViolation = violation("fk_review_tag_review");
    when(reviewRepository.saveAndFlush(any(Review.class))).thenThrow(otherViolation);

    assertThatThrownBy(() -> create(List.of())).isSameAs(otherViolation);
  }

  @Test
  @DisplayName("원인이 Hibernate 제약 위반이 아닌 무결성 오류도 그대로 던진다")
  void integrityViolationWithoutConstraintNameIsRethrown() {
    givenInfo(info(BUYER_ID, CONFIRMED_AT, true));
    DataIntegrityViolationException unknown = new DataIntegrityViolationException("unknown");
    when(reviewRepository.saveAndFlush(any(Review.class))).thenThrow(unknown);

    assertThatThrownBy(() -> create(List.of())).isSameAs(unknown);
  }

  private Long create(List<ReviewTag> tags) {
    return reviewService.create(BUYER_ID, SELLER_ORDER_ID, RATING, CONTENT, tags, NOW);
  }

  private void assertRejected(ReviewErrorCode errorCode) {
    assertThatThrownBy(() -> create(List.of()))
      .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
    verify(reviewRepository, never()).saveAndFlush(any());
  }

  private void givenInfo(SellerOrderReviewInfo info) {
    when(orderQuery.findSellerOrderReviewInfo(SELLER_ORDER_ID)).thenReturn(Optional.of(info));
  }

  private static SellerOrderReviewInfo info(long buyerId, LocalDateTime confirmedAt, boolean hasConfirmedItem) {
    return new SellerOrderReviewInfo(SELLER_ORDER_ID, 9L, buyerId, 20L, confirmedAt, hasConfirmedItem);
  }

  private static DataIntegrityViolationException violation(String constraintName) {
    return new DataIntegrityViolationException("violation",
      new ConstraintViolationException("violation", new SQLException("violation"), constraintName));
  }
}
