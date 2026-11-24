package com.justcommit.backend.market.review.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewTest {

  private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 11, 24, 10, 0, 0);

  @Test
  @DisplayName("작성 기한은 구매 확정 시각에서 3일 뒤다")
  void writableUntilIsThreeDaysAfterConfirmation() {
    assertThat(Review.writableUntil(CONFIRMED_AT)).isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 0, 0));
  }

  @Test
  @DisplayName("구매 확정 직후와 3일이 되기 직전에는 쓸 수 있다")
  void withinWritePeriod() {
    assertThat(Review.isWithinWritePeriod(CONFIRMED_AT, CONFIRMED_AT)).isTrue();
    assertThat(Review.isWithinWritePeriod(CONFIRMED_AT, CONFIRMED_AT.plusDays(3).minusNanos(1))).isTrue();
  }

  @Test
  @DisplayName("정확히 3일째부터는 쓸 수 없다")
  void notWithinWritePeriodFromExactlyThreeDays() {
    assertThat(Review.isWithinWritePeriod(CONFIRMED_AT, CONFIRMED_AT.plusDays(3))).isFalse();
    assertThat(Review.isWithinWritePeriod(CONFIRMED_AT, CONFIRMED_AT.plusDays(3).plusNanos(1))).isFalse();
  }

  @Test
  @DisplayName("리뷰를 만들면 받은 값을 그대로 갖고, 삭제 시각은 비어 있다")
  void create() {
    Review review = new Review(1L, 2L, 10L, 20L, new BigDecimal("4.5"), "잎이 싱싱하고 포장이 꼼꼼했어요",
      List.of(ReviewTag.CAREFUL_PACKING, ReviewTag.HEALTHY_ARRIVAL));

    assertThat(review.getSellerOrderId()).isEqualTo(1L);
    assertThat(review.getOrderId()).isEqualTo(2L);
    assertThat(review.getBuyerId()).isEqualTo(10L);
    assertThat(review.getSellerId()).isEqualTo(20L);
    assertThat(review.getRating()).isEqualByComparingTo("4.5");
    assertThat(review.getContent()).isEqualTo("잎이 싱싱하고 포장이 꼼꼼했어요");
    assertThat(review.getTags()).containsExactlyInAnyOrder(ReviewTag.CAREFUL_PACKING, ReviewTag.HEALTHY_ARRIVAL);
    assertThat(review.getDeletedAt()).isNull();
  }

  @Test
  @DisplayName("태그 없이도 만들 수 있다")
  void createWithoutTags() {
    Review review = new Review(1L, 2L, 10L, 20L, new BigDecimal("5.0"), "설명과 같은 상태로 잘 받았습니다", List.of());

    assertThat(review.getTags()).isEmpty();
  }
}
