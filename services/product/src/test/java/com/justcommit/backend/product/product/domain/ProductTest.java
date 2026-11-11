package com.justcommit.backend.product.product.domain;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.species.domain.Species;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

class ProductTest {

    // 테스트용 품종을 만드는 메서드
    private Species species(){
        Species species = BeanUtils.instantiateClass(Species.class);
        //Species 는 생성자가 protected 여서  BeanUtils.instantiateClass는 생성자 접근 제한을 무시하고 강제로 객체 생성

        // Null 이 될 수 없는 값들만 집어넣음
        ReflectionTestUtils.setField(species, "name", "몬스테라");
        return species;
    }

    // 상품 생성
    private Product create(Species species){
        return Product.create(species, 1L, "몬스테라 분양합니다", "설명", new BigDecimal("30000"),
                null, null, null, List.of("https://example.com/a.jpg"));
    }

    @Test
    @DisplayName("상품을 생성하면 검수 중 · 숨김 아님 · 검수 버전 0 상태")
    void create() {
        Product product = create(species());

        assertThat(product.getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        assertThat(product.getReviewVersion()).isZero();
        assertThat(product.isHidden()).isFalse();
    }
    @Test
    @DisplayName("결제 대기 중인 상품도 숨길 수 있음")
    void hidePaymentPendingProduct() {
        Product product = create(species());
        ReflectionTestUtils.setField(product, "status", ProductStatus.PAYMENT_PENDING);

        product.changeHidden(true);

        assertThat(product.isHidden()).isTrue();
    }

    @Test
    @DisplayName("삭제된 상품은 숨김을 바꿀 수 없음 (PRODUCT_NOT_HIDEABLE)")
    void hideDeletedProduct() {
        Product product = create(species());
        product.delete();

        assertThatThrownBy(() -> product.changeHidden(true))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_HIDEABLE);
    }


    // 품종 id까지 넣은 품종 (품종이 바뀌었는지 비교할 때)
    private Species species(Long id) {
        Species species = species();
        ReflectionTestUtils.setField(species, "id", id);
        return species;
    }

    // 상품 상태를 직접 지정 (검수 통과 메서드는 #343에서 생김)
    private Product withStatus(Product product, ProductStatus status) {
        ReflectionTestUtils.setField(product, "status", status);
        return product;
    }

    // 제목 · 품종 · 사진만 바꿔 수정
    private ReviewScope update(Product product, Species species, String title, List<String> imageUrls) {
        return product.update(species, title, "설명", new BigDecimal("30000"), null, null, null, imageUrls);
    }

    // ── 수정: 상태 · 검수 범위 · 검수 버전 ───────────────────

    @Test
    @DisplayName("판매 중 상품의 글만 고치면 판매 중을 유지하고 글 검사만 하며, 검수 버전이 1 오름")
    void updateTextOnlyKeepsActive() {
        Species species = species(1L);
        Product product = withStatus(create(species), ProductStatus.ACTIVE);

        ReviewScope scope = update(product, species, "새 제목", List.of("https://example.com/a.jpg"));

        assertThat(scope).isEqualTo(ReviewScope.TEXT_ONLY);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(product.getReviewVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("판매 중 상품의 사진을 바꾸면 검수 중으로 돌아가고 사진 검수부터 함")
    void updateImagesGoesToReview() {
        Species species = species(1L);
        Product product = withStatus(create(species), ProductStatus.ACTIVE);

        ReviewScope scope = update(product, species, "몬스테라 분양합니다", List.of("https://example.com/b.jpg"));

        assertThat(scope).isEqualTo(ReviewScope.FULL);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        assertThat(product.getReviewVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("품종을 바꾸면 사진이 같아도 사진 검수부터 함 (품종 대조가 사진 검수 단계에 있음)")
    void updateSpeciesGoesToReview() {
        Product product = withStatus(create(species(1L)), ProductStatus.ACTIVE);

        ReviewScope scope = update(product, species(2L), "몬스테라 분양합니다", List.of("https://example.com/a.jpg"));

        assertThat(scope).isEqualTo(ReviewScope.FULL);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
    }

    @Test
    @DisplayName("사진 순서만 바꾸면 같은 사진으로 보고 글 검사만 함")
    void reorderImagesIsTextOnly() {
        Species species = species(1L);
        Product product = withStatus(Product.create(species, 1L, "몬스테라 분양합니다", "설명", new BigDecimal("30000"),
                null, null, null, List.of("https://example.com/a.jpg", "https://example.com/b.jpg")), ProductStatus.ACTIVE);

        ReviewScope scope = update(product, species, "몬스테라 분양합니다",
                List.of("https://example.com/b.jpg", "https://example.com/a.jpg"));

        assertThat(scope).isEqualTo(ReviewScope.TEXT_ONLY);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(product.getThumbnailUrl()).isEqualTo("https://example.com/b.jpg");
    }

    @Test
    @DisplayName("반려된 상품은 글만 고쳐도 다시 검수 중이 됨")
    void updateRejectedGoesToReview() {
        Species species = species(1L);
        Product product = withStatus(create(species), ProductStatus.REJECTED);

        ReviewScope scope = update(product, species, "새 제목", List.of("https://example.com/a.jpg"));

        assertThat(scope).isEqualTo(ReviewScope.TEXT_ONLY);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
    }

    @Test
    @DisplayName("검수 중 상품도 수정할 수 있고, 수정할 때마다 검수 버전이 1씩 오름")
    void reviewVersionIncreasesEveryUpdate() {
        Species species = species(1L);
        Product product = create(species);

        update(product, species, "첫 수정", List.of("https://example.com/a.jpg"));
        update(product, species, "두 번째 수정", List.of("https://example.com/b.jpg"));

        assertThat(product.getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        assertThat(product.getReviewVersion()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, names = {"PAYMENT_PENDING", "SOLD_OUT"})
    @DisplayName("결제 대기 · 판매 완료 상품은 수정할 수 없음 (PRODUCT_NOT_EDITABLE), 검수 버전도 그대로")
    void updateNotEditable(ProductStatus status) {
        Species species = species(1L);
        Product product = withStatus(create(species), status);

        assertThatThrownBy(() -> update(product, species, "새 제목", List.of("https://example.com/a.jpg")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_EDITABLE);
        assertThat(product.getStatus()).isEqualTo(status);
        assertThat(product.getReviewVersion()).isZero();
    }

    // ── 삭제 ───────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, names = {"ACTIVE", "REVIEW_PENDING", "REJECTED"})
    @DisplayName("판매 중 · 검수 중 · 반려 상품은 삭제할 수 있음")
    void deleteChangeable(ProductStatus status) {
        Product product = withStatus(create(species()), status);

        product.delete();

        assertThat(product.getStatus()).isEqualTo(ProductStatus.DELETED);
    }

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, names = {"PAYMENT_PENDING", "SOLD_OUT"})
    @DisplayName("결제 대기 · 판매 완료 상품은 삭제할 수 없음 (PRODUCT_NOT_DELETABLE)")
    void deleteNotDeletable(ProductStatus status) {
        Product product = withStatus(create(species()), status);

        assertThatThrownBy(product::delete)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_DELETABLE);
        assertThat(product.getStatus()).isEqualTo(status);
    }

    // ── 노출 ───────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, names = {"REVIEW_PENDING", "REJECTED"})
    @DisplayName("검수 중 · 반려 상품은 판매자 본인에게만 보이고(남 · 비회원은 PRODUCT_NOT_FOUND), 구매할 수 없음")
    void inReviewVisibleToOwnerOnly(ProductStatus status) {
        Product product = withStatus(create(species()), status);

        assertThat(product.isInReview()).isTrue();
        product.checkViewableBy(1L);   // 판매자 본인은 예외 없음
        assertThatThrownBy(() -> product.checkViewableBy(2L))      // 다른 회원
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
        assertThatThrownBy(() -> product.checkViewableBy(null))    // 비회원
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
        assertThat(product.isPurchasable()).isFalse();
    }

    @Test
    @DisplayName("숨긴 상품은 판매자 본인만 보고, 다른 회원에게는 PRODUCT_HIDDEN. 숨긴 검수 중 상품은 남에게 PRODUCT_NOT_FOUND")
    void hiddenProductViewRules() {
        Product hidden = withStatus(create(species()), ProductStatus.ACTIVE);
        hidden.changeHidden(true);
        Product hiddenInReview = create(species());
        hiddenInReview.changeHidden(true);

        hidden.checkViewableBy(1L);
        assertThatThrownBy(() -> hidden.checkViewableBy(2L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_HIDDEN);
        assertThatThrownBy(() -> hiddenInReview.checkViewableBy(2L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
    }
}
