package com.justcommit.backend.market.review.domain;

import com.justcommit.backend.market.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
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
    BigDecimal rating, String content, Collection<ReviewTag> tags) {
    this.sellerOrderId = sellerOrderId;
    this.orderId = orderId;
    this.buyerId = buyerId;
    this.sellerId = sellerId;
    this.rating = rating;
    this.content = content;
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
}
