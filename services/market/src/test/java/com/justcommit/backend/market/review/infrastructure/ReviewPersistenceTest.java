package com.justcommit.backend.market.review.infrastructure;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.review.domain.Review;
import com.justcommit.backend.market.review.domain.ReviewTag;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V13 리뷰 테이블이 엔티티와 맞는지(validate), 유니크 · 외래 키가 실제 DB에서 동작하는지 확인한다 (#582)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewPersistenceTest {

  private static final long SELLER_ORDER_ID = 100L;

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    SharedPostgres.register(registry);
  }

  @Autowired ReviewRepository reviewRepository;
  @Autowired JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.execute("truncate table review_tag, review");
  }

  @Test
  @DisplayName("리뷰와 태그를 저장하고 다시 읽는다")
  void saveAndRead() {
    Review saved = reviewRepository.saveAndFlush(review(SELLER_ORDER_ID,
      List.of(ReviewTag.CAREFUL_PACKING, ReviewTag.HEALTHY_ARRIVAL)));

    Review read = reviewRepository.findById(saved.getId()).orElseThrow();
    assertThat(read.getSellerOrderId()).isEqualTo(SELLER_ORDER_ID);
    assertThat(read.getRating()).isEqualByComparingTo("4.5");
    assertThat(read.getContent()).isEqualTo("잎이 싱싱하고 포장이 꼼꼼했어요");
    assertThat(read.getCreatedAt()).isNotNull();
    assertThat(read.getDeletedAt()).isNull();
    assertThat(jdbcTemplate.queryForList(
      "select tag from review_tag where review_id = ? order by tag", String.class, saved.getId()))
      .containsExactly("CAREFUL_PACKING", "HEALTHY_ARRIVAL");
  }

  @Test
  @DisplayName("리뷰가 있는 판매자 주문만 존재한다고 답한다")
  void existsBySellerOrderId() {
    reviewRepository.saveAndFlush(review(SELLER_ORDER_ID, List.of()));

    assertThat(reviewRepository.existsBySellerOrderId(SELLER_ORDER_ID)).isTrue();
    assertThat(reviewRepository.existsBySellerOrderId(SELLER_ORDER_ID + 1)).isFalse();
  }

  @Test
  @DisplayName("삭제한 리뷰도 존재한다고 답한다 (삭제한 판매자 주문에 다시 쓸 수 없게)")
  void existsEvenWhenDeleted() {
    Review saved = reviewRepository.saveAndFlush(review(SELLER_ORDER_ID, List.of()));
    jdbcTemplate.update("update review set deleted_at = now() where id = ?", saved.getId());

    assertThat(reviewRepository.existsBySellerOrderId(SELLER_ORDER_ID)).isTrue();
  }

  @Test
  @DisplayName("같은 판매자 주문에 두 번 저장하면 유니크 제약에 걸리고, 걸린 제약 이름을 알 수 있다")
  void duplicateSellerOrderViolatesUniqueConstraint() {
    reviewRepository.saveAndFlush(review(SELLER_ORDER_ID, List.of()));

    assertThatThrownBy(() -> reviewRepository.saveAndFlush(review(SELLER_ORDER_ID, List.of())))
      .isInstanceOf(DataIntegrityViolationException.class)
      .satisfies(e -> {
        assertThat(e.getCause()).isInstanceOf(ConstraintViolationException.class);
        assertThat(((ConstraintViolationException) e.getCause()).getConstraintName())
          .isEqualTo(Review.UK_SELLER_ORDER_ID);
      });
    assertThat(jdbcTemplate.queryForObject("select count(*) from review", Long.class)).isEqualTo(1);
  }

  @Test
  @DisplayName("없는 리뷰의 태그는 저장할 수 없다 (외래 키)")
  void tagRequiresReview() {
    assertThatThrownBy(() -> jdbcTemplate.update(
      "insert into review_tag (review_id, tag) values (?, ?)", 999_999L, "GOOD_PRICE"))
      .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static Review review(long sellerOrderId, List<ReviewTag> tags) {
    return new Review(sellerOrderId, 1L, 10L, 20L, new BigDecimal("4.5"), "잎이 싱싱하고 포장이 꼼꼼했어요", tags);
  }
}
