package com.justcommit.backend.market.review.domain;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewTest {

  private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 11, 24, 10, 0, 0);
  private static final String CONTENT = "잎이 싱싱하고 포장이 꼼꼼했어요";

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

  @ParameterizedTest
  @ValueSource(strings = {"0.5", "1.0", "4.5", "5.0", "4.50"})
  @DisplayName("별점은 0.5 ~ 5.0 사이의 0.5 단위면 만들 수 있다")
  void validRating(String rating) {
    Review review = review(new BigDecimal(rating), CONTENT, List.of());

    assertThat(review.getRating()).isEqualByComparingTo(rating);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.0", "0.4", "5.5", "2.3", "4.55", "-1.0"})
  @DisplayName("별점이 범위를 벗어나거나 0.5 단위가 아니면 400이다")
  void invalidRating(String rating) {
    assertInvalid(() -> review(new BigDecimal(rating), CONTENT, List.of()));
  }

  @Test
  @DisplayName("별점이 없으면 400이다")
  void nullRating() {
    assertInvalid(() -> review(null, CONTENT, List.of()));
  }

  @Test
  @DisplayName("리뷰 내용은 앞뒤 공백을 빼고 저장한다")
  void contentIsStripped() {
    Review review = review(new BigDecimal("4.5"), "  \n" + CONTENT + "\t  ", List.of());

    assertThat(review.getContent()).isEqualTo(CONTENT);
  }

  @Test
  @DisplayName("앞뒤 공백을 뺀 리뷰 내용이 정확히 10자, 300자면 만들 수 있다")
  void contentLengthBoundaryAccepted() {
    assertThat(review(new BigDecimal("4.5"), " " + "가".repeat(10) + " ", List.of()).getContent()).hasSize(10);
    assertThat(review(new BigDecimal("4.5"), " " + "가".repeat(300) + " ", List.of()).getContent()).hasSize(300);
  }

  @Test
  @DisplayName("앞뒤 공백을 뺀 리뷰 내용이 9자 이하거나 301자 이상이면 400이다 (공백으로 길이를 채워도 안 된다)")
  void contentLengthOutOfRangeRejected() {
    assertInvalid(() -> review(new BigDecimal("4.5"), "가".repeat(9), List.of()));
    assertInvalid(() -> review(new BigDecimal("4.5"), "   " + "가".repeat(9) + "   ", List.of()));
    assertInvalid(() -> review(new BigDecimal("4.5"), "가".repeat(301), List.of()));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\t\n"})
  @DisplayName("리뷰 내용이 없거나 공백뿐이면 400이다")
  void blankContentRejected(String content) {
    assertInvalid(() -> review(new BigDecimal("4.5"), content, List.of()));
  }

  @Test
  @DisplayName("태그 목록 자체가 null이면 400이다 (요청의 생략 · null은 요청 DTO가 빈 목록으로 바꾼다)")
  void nullTagsRejected() {
    assertInvalid(() -> review(new BigDecimal("4.5"), CONTENT, null));
  }

  @Test
  @DisplayName("태그 목록 안에 null이 있으면 400이다")
  void nullTagElementRejected() {
    assertInvalid(() -> review(new BigDecimal("4.5"), CONTENT, Arrays.asList(ReviewTag.GOOD_PRICE, null)));
  }

  @Test
  @DisplayName("같은 태그가 두 번 있으면 400이다")
  void duplicateTagsRejected() {
    assertInvalid(() -> review(new BigDecimal("4.5"), CONTENT,
      List.of(ReviewTag.GOOD_PRICE, ReviewTag.CAREFUL_PACKING, ReviewTag.GOOD_PRICE)));
  }

  @Test
  @DisplayName("삭제 기한은 리뷰 작성 시각에서 3일 뒤다 (#584)")
  void deletableUntilIsThreeDaysAfterCreation() {
    Review review = createdAt(CONFIRMED_AT);

    assertThat(review.deletableUntil()).isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 0, 0));
  }

  @Test
  @DisplayName("작성 직후와 72시간이 되기 직전에는 지울 수 있고, 정확히 72시간째부터는 지울 수 없다 (#584)")
  void deletePeriodBoundary() {
    Review review = createdAt(CONFIRMED_AT);

    assertThat(review.isWithinDeletePeriod(CONFIRMED_AT)).isTrue();
    assertThat(review.isWithinDeletePeriod(CONFIRMED_AT.plusHours(72).minusNanos(1))).isTrue();
    assertThat(review.isWithinDeletePeriod(CONFIRMED_AT.plusHours(72))).isFalse();
    assertThat(review.isWithinDeletePeriod(CONFIRMED_AT.plusHours(72).plusNanos(1))).isFalse();
  }

  private static Review createdAt(LocalDateTime createdAt) {
    Review review = review(new BigDecimal("4.5"), CONTENT, List.of());
    ReflectionTestUtils.setField(review, "createdAt", createdAt);
    return review;
  }

  private static Review review(BigDecimal rating, String content, List<ReviewTag> tags) {
    return new Review(1L, 2L, 10L, 20L, rating, content, tags);
  }

  private static void assertInvalid(ThrowingCallable callable) {
    assertThatThrownBy(callable)
      .isInstanceOfSatisfying(BusinessException.class, e ->
        assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_FIELD_ERROR));
  }
}
