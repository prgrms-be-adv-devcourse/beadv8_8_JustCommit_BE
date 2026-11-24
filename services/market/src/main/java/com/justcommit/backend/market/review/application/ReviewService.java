package com.justcommit.backend.market.review.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.SellerOrderReviewInfo;
import com.justcommit.backend.market.review.domain.Review;
import com.justcommit.backend.market.review.domain.ReviewErrorCode;
import com.justcommit.backend.market.review.domain.ReviewTag;
import com.justcommit.backend.market.review.infrastructure.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewService {

  private final OrderQuery orderQuery;
  private final ReviewRepository reviewRepository;

  // 리뷰 작성 (#582). 판매자 주문 하나에 한 번, 구매자 본인만, 구매 확정 후 작성 기간 안에만 쓸 수 있다
  @Transactional
  public Long create(Long buyerId, Long sellerOrderId, BigDecimal rating, String content, List<ReviewTag> tags) {
    return create(buyerId, sellerOrderId, rating, content, tags, LocalDateTime.now());
  }

  // 테스트가 현재 시각을 고정할 수 있게 now를 받는다
  @Transactional
  Long create(Long buyerId, Long sellerOrderId, BigDecimal rating, String content, List<ReviewTag> tags,
    LocalDateTime now) {
    SellerOrderReviewInfo info = orderQuery.findSellerOrderReviewInfo(sellerOrderId)
      .filter(found -> found.buyerId().equals(buyerId))
      .orElseThrow(() -> new BusinessException(ReviewErrorCode.REVIEW_SELLER_ORDER_NOT_FOUND));
    if (info.confirmedAt() == null) {
      throw new BusinessException(ReviewErrorCode.REVIEW_NOT_CONFIRMED);
    }
    if (!info.hasConfirmedItem()) {
      throw new BusinessException(ReviewErrorCode.REVIEW_NO_CONFIRMED_ITEM);
    }
    // 본인 주문인지 먼저 본 뒤에 확인한다: 남의 주문에 리뷰가 있는지 409로 드러나지 않게
    if (reviewRepository.existsBySellerOrderId(sellerOrderId)) {
      throw new BusinessException(ReviewErrorCode.REVIEW_ALREADY_WRITTEN);
    }
    if (!Review.isWithinWritePeriod(info.confirmedAt(), now)) {
      throw new BusinessException(ReviewErrorCode.REVIEW_PERIOD_EXPIRED);
    }

    Review review = new Review(sellerOrderId, info.orderId(), buyerId, info.sellerId(), rating, content, tags);
    try {
      // 위 확인을 동시에 통과한 요청은 seller_order_id 유니크가 막는다. 커밋이 아니라 여기서 위반이 나도록 바로 보낸다
      return reviewRepository.saveAndFlush(review).getId();
    } catch (DataIntegrityViolationException e) {
      if (isDuplicateSellerOrder(e)) {
        throw new BusinessException(ReviewErrorCode.REVIEW_ALREADY_WRITTEN);
      }
      throw e; // 예상하지 못한 제약 위반은 그대로 던진다 (500 + 로그)
    }
  }

  private boolean isDuplicateSellerOrder(DataIntegrityViolationException e) {
    return e.getCause() instanceof ConstraintViolationException violation
      && Review.UK_SELLER_ORDER_ID.equals(violation.getConstraintName());
  }
}
