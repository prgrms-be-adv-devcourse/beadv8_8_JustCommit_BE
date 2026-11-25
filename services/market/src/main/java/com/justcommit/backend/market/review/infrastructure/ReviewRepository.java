package com.justcommit.backend.market.review.infrastructure;

import com.justcommit.backend.market.review.domain.Review;
import com.justcommit.backend.market.review.domain.ReviewTag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {

  // 삭제한 리뷰도 센다 (삭제 여부를 조건에 넣지 않는다): 삭제한 판매자 주문에도 다시 쓸 수 없다
  boolean existsBySellerOrderId(Long sellerOrderId);

  // 주문 상세의 판매자별 리뷰 상태용 (#644). 삭제한 리뷰도 읽는다 (삭제함 상태 판정, 본문은 응답에 넣지 않음)
  List<Review> findBySellerOrderIdIn(Collection<Long> sellerOrderIds);

  // 리뷰가 있는 판매자 주문 id (#585 작성 가능 목록에서 뺄 것). existsBySellerOrderId처럼 삭제한 리뷰도 센다
  @Query("select r.sellerOrderId from Review r where r.sellerOrderId in :sellerOrderIds")
  List<Long> findSellerOrderIdsIn(@Param("sellerOrderIds") Collection<Long> sellerOrderIds);

  // 판매자 리뷰 목록 (#583): 삭제하지 않은 리뷰만. 정렬은 pageable로 받는다 (태그는 따로 묶어서 읽는다)
  Page<Review> findBySellerIdAndDeletedAtIsNull(Long sellerId, Pageable pageable);

  // 내 리뷰 목록 (#584): 삭제하지 않은 내 리뷰만. 정렬은 pageable로 받는다
  Page<Review> findByBuyerIdAndDeletedAtIsNull(Long buyerId, Pageable pageable);

  // 리뷰 삭제 (#584): 작성자이고 아직 삭제하지 않은 행만 바꾼다. 같은 리뷰를 동시에 지우면 한 요청만 1을 받고
  // 나머지는 0(먼저 저장한 deleted_at은 그대로)
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("""
    update Review r set r.deletedAt = :now, r.updatedAt = :now
    where r.id = :reviewId and r.buyerId = :buyerId and r.deletedAt is null
    """)
  int softDelete(@Param("reviewId") Long reviewId, @Param("buyerId") Long buyerId, @Param("now") LocalDateTime now);

  // 목록 한 페이지의 태그를 한 번에 읽는다. 컬렉션 fetch join은 페이지를 메모리에서 자르게 되어 쓰지 않는다
  @Query("select r.id as reviewId, t as tag from Review r join r.tags t where r.id in :reviewIds")
  List<ReviewTagRow> findTagsByReviewIds(@Param("reviewIds") Collection<Long> reviewIds);

  // 별점별 리뷰 수 (#583). 태그와 조인하지 않아 태그가 여러 개인 리뷰도 한 번만 센다 (평균 · 전체 수도 이 결과로 계산)
  @Query("""
    select r.rating as rating, count(r) as count
    from Review r
    where r.sellerId = :sellerId and r.deletedAt is null
    group by r.rating
    """)
  List<RatingCountRow> countByRating(@Param("sellerId") Long sellerId);

  // 태그별 리뷰 수 (#583). (review_id, tag) PK라 리뷰 하나는 같은 태그에 한 번만 들어간다
  @Query("""
    select t as tag, count(r) as count
    from Review r join r.tags t
    where r.sellerId = :sellerId and r.deletedAt is null
    group by t
    """)
  List<TagCountRow> countByTag(@Param("sellerId") Long sellerId);

  interface ReviewTagRow {
    Long getReviewId();

    ReviewTag getTag();
  }

  interface RatingCountRow {
    BigDecimal getRating();

    long getCount();
  }

  interface TagCountRow {
    ReviewTag getTag();

    long getCount();
  }
}
