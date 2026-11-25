package com.justcommit.backend.market.review.application;

import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.review.domain.Review;
import com.justcommit.backend.market.review.domain.ReviewTag;
import com.justcommit.backend.market.review.infrastructure.ReviewRepository;
import com.justcommit.backend.market.review.infrastructure.ReviewRepository.RatingCountRow;
import com.justcommit.backend.market.review.infrastructure.ReviewRepository.ReviewTagRow;
import com.justcommit.backend.market.review.infrastructure.ReviewRepository.TagCountRow;
import com.justcommit.backend.market.review.presentation.MyReviewResponse;
import com.justcommit.backend.market.review.presentation.ReviewSummaryResponse;
import com.justcommit.backend.market.review.presentation.ReviewSummaryResponse.RatingCount;
import com.justcommit.backend.market.review.presentation.ReviewSummaryResponse.TagCount;
import com.justcommit.backend.market.review.presentation.SellerReviewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewReadService {

  private final ReviewRepository reviewRepository;
  private final OrderQuery orderQuery;
  private final MemberQuery memberQuery;

  // 판매자 리뷰 목록 (#583). 페이지에 든 리뷰의 태그 · 구매 상품명 · 작성자 닉네임을 각각 한 번에 묶어서 읽는다
  public PagingResponse<SellerReviewResponse> sellerReviews(Long sellerId, Pageable pageable) {
    Page<Review> page = reviewRepository.findBySellerIdAndDeletedAtIsNull(sellerId, pageable);
    List<Review> reviews = page.getContent();
    if (reviews.isEmpty()) {
      return PagingResponse.from(page.map(review -> toResponse(review, Map.of(), Map.of(), Map.of())));
    }
    Map<Long, List<ReviewTag>> tags = tagsByReview(reviews.stream().map(Review::getId).toList());
    Map<Long, List<String>> productNames = orderQuery.getConfirmedProductNames(
      reviews.stream().map(Review::getSellerOrderId).distinct().toList());
    Map<Long, String> nicknames = memberQuery.getNicknames(
      reviews.stream().map(Review::getBuyerId).distinct().toList());
    return PagingResponse.from(page.map(review -> toResponse(review, tags, productNames, nicknames)));
  }

  // 내 리뷰 목록 (#584). 페이지에 든 리뷰의 태그 · 구매 상품명 · 주문일 · 판매자 닉네임을 각각 한 번에 묶어서 읽는다.
  // 삭제 기한이 지난 리뷰도 목록에는 남는다 (화면이 deletableUntil로 삭제 버튼을 정한다)
  public PagingResponse<MyReviewResponse> myReviews(Long buyerId, Pageable pageable) {
    Page<Review> page = reviewRepository.findByBuyerIdAndDeletedAtIsNull(buyerId, pageable);
    List<Review> reviews = page.getContent();
    if (reviews.isEmpty()) {
      return PagingResponse.from(page.map(review -> toMyResponse(review, Map.of(), Map.of(), Map.of(), Map.of())));
    }
    Map<Long, List<ReviewTag>> tags = tagsByReview(reviews.stream().map(Review::getId).toList());
    Map<Long, List<String>> productNames = orderQuery.getConfirmedProductNames(
      reviews.stream().map(Review::getSellerOrderId).distinct().toList());
    Map<Long, LocalDateTime> orderedAts = orderQuery.getOrderedAts(
      reviews.stream().map(Review::getOrderId).distinct().toList());
    Map<Long, String> nicknames = memberQuery.getNicknames(
      reviews.stream().map(Review::getSellerId).distinct().toList());
    return PagingResponse.from(page.map(review -> toMyResponse(review, tags, productNames, orderedAts, nicknames)));
  }

  // 판매자 리뷰 집계 (#583). 평균 · 전체 수 · 별점별 개수는 태그와 조인하지 않은 별점별 집계 하나에서 계산해
  // 태그가 여러 개인 리뷰도 한 번만 들어간다. 태그별 개수는 따로 센다
  public ReviewSummaryResponse summary(Long sellerId) {
    Map<BigDecimal, Long> countsByRating = new TreeMap<>(); // BigDecimal compareTo로 비교 (5.0과 5.00을 같게)
    long reviewCount = 0;
    BigDecimal ratingSum = BigDecimal.ZERO;
    for (RatingCountRow row : reviewRepository.countByRating(sellerId)) {
      countsByRating.merge(row.getRating(), row.getCount(), Long::sum);
      reviewCount += row.getCount();
      ratingSum = ratingSum.add(row.getRating().multiply(BigDecimal.valueOf(row.getCount())));
    }
    List<RatingCount> ratingCounts = Review.RATINGS.stream()
      .map(rating -> new RatingCount(rating, countsByRating.getOrDefault(rating, 0L)))
      .toList();
    // 별점은 Review가 0.5 ~ 5.0 · 0.5 단위로만 저장한다. 구간 밖 값을 평균 · 전체 수에만 넣는 예외 계약은 두지 않고,
    // 그런 값이 있으면 정상 응답을 만들지 않는다 (처리 방식은 발견되면 정한다)
    long bucketed = ratingCounts.stream().mapToLong(RatingCount::count).sum();
    if (bucketed != reviewCount) {
      throw new IllegalStateException("별점 구간에 맞지 않는 리뷰가 있음: sellerId=" + sellerId
        + ", ratings=" + countsByRating.keySet());
    }
    BigDecimal averageRating = reviewCount == 0
      ? null
      : ratingSum.divide(BigDecimal.valueOf(reviewCount), 1, RoundingMode.HALF_UP);

    Map<ReviewTag, Long> countsByTag = new EnumMap<>(ReviewTag.class);
    for (TagCountRow row : reviewRepository.countByTag(sellerId)) {
      countsByTag.put(row.getTag(), row.getCount());
    }
    List<TagCount> tagCounts = Arrays.stream(ReviewTag.values())
      .map(tag -> new TagCount(tag, countsByTag.getOrDefault(tag, 0L)))
      .toList();
    return new ReviewSummaryResponse(averageRating, reviewCount, ratingCounts, tagCounts);
  }

  // 리뷰 id → 태그 (enum 순서)
  private Map<Long, List<ReviewTag>> tagsByReview(List<Long> reviewIds) {
    Map<Long, Set<ReviewTag>> tags = new HashMap<>();
    for (ReviewTagRow row : reviewRepository.findTagsByReviewIds(reviewIds)) {
      tags.computeIfAbsent(row.getReviewId(), ignored -> EnumSet.noneOf(ReviewTag.class)).add(row.getTag());
    }
    Map<Long, List<ReviewTag>> result = new HashMap<>();
    tags.forEach((reviewId, set) -> result.put(reviewId, List.copyOf(set)));
    return result;
  }

  private static MyReviewResponse toMyResponse(Review review, Map<Long, List<ReviewTag>> tags,
    Map<Long, List<String>> productNames, Map<Long, LocalDateTime> orderedAts, Map<Long, String> nicknames) {
    return new MyReviewResponse(review.getId(), review.getSellerId(), nicknames.get(review.getSellerId()),
      review.getOrderId(), orderedAts.get(review.getOrderId()), review.getRating(), review.getContent(),
      tags.getOrDefault(review.getId(), List.of()), productNames.getOrDefault(review.getSellerOrderId(), List.of()),
      review.getCreatedAt(), review.deletableUntil());
  }

  private static SellerReviewResponse toResponse(Review review, Map<Long, List<ReviewTag>> tags,
    Map<Long, List<String>> productNames, Map<Long, String> nicknames) {
    return new SellerReviewResponse(review.getId(), review.getRating(), review.getContent(),
      tags.getOrDefault(review.getId(), List.of()), review.getCreatedAt(), nicknames.get(review.getBuyerId()),
      productNames.getOrDefault(review.getSellerOrderId(), List.of()));
  }
}
