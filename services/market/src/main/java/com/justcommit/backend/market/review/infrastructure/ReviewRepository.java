package com.justcommit.backend.market.review.infrastructure;

import com.justcommit.backend.market.review.domain.Review;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {

  // 삭제한 리뷰도 센다 (삭제 여부를 조건에 넣지 않는다): 삭제한 판매자 주문에도 다시 쓸 수 없다
  boolean existsBySellerOrderId(Long sellerOrderId);
}
