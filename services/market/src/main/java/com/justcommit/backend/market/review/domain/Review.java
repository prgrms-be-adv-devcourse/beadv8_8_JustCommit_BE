package com.justcommit.backend.market.review.domain;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.market.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

// 판매자 평가 리뷰 (#582). 판매자 주문 하나에 한 번만 쓴다 (seller_order_id 유니크).
// 삭제는 deletedAt만 채우고 행을 남기므로, 삭제한 판매자 주문에도 다시 쓸 수 없다
@Entity
@Table(
  name = "review",
  uniqueConstraints = @UniqueConstraint(
    name = Review.UK_SELLER_ORDER_ID,
    columnNames = "seller_order_id"
  ))
@Getter
@NoArgsConstructor
public class Review extends BaseTimeEntity {

  // 같은 판매자 주문에 두 번 저장하면 걸리는 제약 이름. 동시 요청의 중복 판별에 쓴다
  public static final String UK_SELLER_ORDER_ID = "uk_review_seller_order_id";

  public static final int CONTENT_MIN_LENGTH = 10;
  public static final int CONTENT_MAX_LENGTH = 300;

  private static final BigDecimal RATING_MIN = new BigDecimal("0.5");
  private static final BigDecimal RATING_MAX = new BigDecimal("5.0");
  private static final BigDecimal RATING_STEP = new BigDecimal("0.5");

  // 쓸 수 있는 별점 10개: 5.0부터 0.5까지 0.5 단위 내림차순. 판매자 리뷰 집계의 별점별 개수 구간 (#583)
  public static final List<BigDecimal> RATINGS = ratings();

  // 작성 기간: 구매 확정 시각부터 이 기간 안에만 쓸 수 있다. 끝났으면(같거나 지남) 쓸 수 없다.
  // 작성 가능한 주문 목록(#585)도 같은 계산을 쓴다
  public static final Duration WRITE_PERIOD = Duration.ofDays(3);

  @Column(nullable = false)
  private Long sellerOrderId;

  @Column(nullable = false)
  private Long orderId;

  @Column(nullable = false)
  private Long buyerId;

  @Column(nullable = false)
  private Long sellerId;

  // 0.5 ~ 5.0, 0.5 단위
  @Column(nullable = false, precision = 2, scale = 1)
  private BigDecimal rating;

  @Column(nullable = false, length = CONTENT_MAX_LENGTH)
  private String content;

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(
    name = "review_tag",
    joinColumns = @JoinColumn(name = "review_id", foreignKey = @ForeignKey(name = "fk_review_tag_review")))
  @Enumerated(EnumType.STRING)
  @Column(name = "tag", nullable = false, length = 30)
  private Set<ReviewTag> tags = EnumSet.noneOf(ReviewTag.class);

  // 삭제 시각 (#584). 비어 있으면 삭제하지 않은 리뷰
  private LocalDateTime deletedAt;

  public Review(Long sellerOrderId, Long orderId, Long buyerId, Long sellerId,
    BigDecimal rating, String content, List<ReviewTag> tags) {
    validateRating(rating);
    String strippedContent = stripAndValidateContent(content);
    validateTags(tags);
    this.sellerOrderId = sellerOrderId;
    this.orderId = orderId;
    this.buyerId = buyerId;
    this.sellerId = sellerId;
    this.rating = rating;
    this.content = strippedContent;
    this.tags.addAll(tags);
  }

  // 작성 기한: 구매 확정 시각 + 작성 기간
  public static LocalDateTime writableUntil(LocalDateTime confirmedAt) {
    return confirmedAt.plus(WRITE_PERIOD);
  }

  // 작성 기간 안인지: 지금 < 구매 확정 시각 + 작성 기간
  public static boolean isWithinWritePeriod(LocalDateTime confirmedAt, LocalDateTime now) {
    return now.isBefore(writableUntil(confirmedAt));
  }

  // 0.5 ~ 5.0, 0.5 단위: 2배 했을 때 정수여야 한다 (4.50은 허용, 2.3 · 4.55는 거절)
  private static void validateRating(BigDecimal rating) {
    if (rating == null || rating.compareTo(RATING_MIN) < 0 || rating.compareTo(RATING_MAX) > 0
      || rating.multiply(BigDecimal.TWO).stripTrailingZeros().scale() > 0) {
      throw invalid("별점은 0.5 ~ 5.0 사이에서 0.5 단위로 입력해 주세요.");
    }
  }

  // 앞뒤 공백을 뺀 길이로 검사하고, 뺀 값을 저장한다 (공백만 채워 길이를 맞추지 못하게)
  private static String stripAndValidateContent(String content) {
    String stripped = content == null ? "" : content.strip();
    if (stripped.length() < CONTENT_MIN_LENGTH || stripped.length() > CONTENT_MAX_LENGTH) {
      throw invalid("리뷰 내용은 앞뒤 공백을 빼고 " + CONTENT_MIN_LENGTH + " ~ " + CONTENT_MAX_LENGTH + "자로 입력해 주세요.");
    }
    return stripped;
  }

  // 요청의 생략 · null은 4번 커밋의 요청 DTO가 빈 목록으로 바꿔 넘긴다. 여기서는 목록 자체가 없으면 거절한다.
  // Set으로 받으면 중복이 조용히 사라지므로 List로 받아 중복을 거절한다
  private static void validateTags(List<ReviewTag> tags) {
    if (tags == null) {
      throw invalid("태그 목록이 없습니다.");
    }
    if (tags.stream().anyMatch(Objects::isNull)) {
      throw invalid("태그에 빈 값이 있습니다.");
    }
    if (new HashSet<>(tags).size() != tags.size()) {
      throw invalid("같은 태그를 두 번 선택할 수 없습니다.");
    }
  }

  private static List<BigDecimal> ratings() {
    List<BigDecimal> ratings = new ArrayList<>();
    for (BigDecimal rating = RATING_MAX; rating.compareTo(RATING_MIN) >= 0; rating = rating.subtract(RATING_STEP)) {
      ratings.add(rating);
    }
    return List.copyOf(ratings);
  }

  private static BusinessException invalid(String message) {
    return new BusinessException(CommonErrorCode.INVALID_FIELD_ERROR, message);
  }
}
