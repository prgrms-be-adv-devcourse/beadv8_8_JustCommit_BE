package com.justcommit.backend.product.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.domain.Product;
import com.justcommit.backend.product.domain.ProductPicture;
import com.justcommit.backend.product.domain.Species;
import com.justcommit.backend.product.domain.exception.ProductErrorCode;
import com.justcommit.backend.product.infrastructure.repository.ProductRepository;
import com.justcommit.backend.product.infrastructure.repository.SpeciesRepository;
import com.justcommit.backend.product.presentation.dto.ProductDetailResponse;
import com.justcommit.backend.product.presentation.dto.ProductRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class) //JUnit - Mockito 연결
public class  ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private SpeciesRepository speciesRepository;

    @InjectMocks
    private ProductService productService;

    private static final Long SELLER_ID = 1L;        // 상품 주인
    private static final Long OTHER_MEMBER_ID = 2L;  // 남의 상품을 건드리는 사람
    private static final Long PRODUCT_ID = 10L;
    private static final Long NEW_SPECIES_ID = 2L;   // 수정할 때 바꿀 품종

    //품종 만들기
    private Species species(Long id, String name, boolean banned) {
        Species species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "id", id);
        ReflectionTestUtils.setField(species, "name", name);
        ReflectionTestUtils.setField(species, "banned", banned);
        return species;
    }

    //상품 만들기
    private Product product(ProductStatus status) {
        Product product = Product.create(species(1L, "몬스테라", false), SELLER_ID,
                "몬스테라 분양합니다", "잘 자란 몬스테라입니다", new BigDecimal("30000"),
                50, "중형", "베란다에서 키웠어요", List.of("https://example.com/a.jpg"));
        ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
        ReflectionTestUtils.setField(product, "status", status);
        return product;
    }

    // 수정 요청
    private ProductRequest updateRequest(List<String> pictureUrls) {
        return new ProductRequest(NEW_SPECIES_ID, "스킨답서스 분양합니다", "새로 올립니다",
                new BigDecimal("25000"), 30, "소형", null, pictureUrls);
    }

    //ProductPicture 객체 목록을 URL 문자열 목록으로 변경
    private List<String> pictureUrlsOf(Product product) {
        return product.getPictures().stream().map(ProductPicture::getS3Url).toList();
    }

    @Test
    @DisplayName("상품을 수정하면 품종과 사진을 포함한 모든 항목이 바뀜")
    void updateProduct() {
        // given
        Product product = product(ProductStatus.ACTIVE);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));
        given(speciesRepository.findById(NEW_SPECIES_ID))
                .willReturn(Optional.of(species(NEW_SPECIES_ID, "스킨답서스", false)));

        // when - service
        productService.updateProduct(SELLER_ID, PRODUCT_ID,
                updateRequest(List.of("https://example.com/c.jpg", "https://example.com/d.jpg")));

        // then
        assertThat(product.getTitle()).isEqualTo("스킨답서스 분양합니다");
        assertThat(product.getPrice()).isEqualByComparingTo("25000");
        assertThat(product.getSpecies().getName()).isEqualTo("스킨답서스");
        assertThat(product.getGrowNote()).isNull();
        assertThat(pictureUrlsOf(product))
                .containsExactly("https://example.com/c.jpg", "https://example.com/d.jpg");
    }

    @Test
    @DisplayName("사진 없이 수정하면 사진 목록이 사라짐")
    void updateProductWithoutPicture(){
        Product product = product(ProductStatus.ACTIVE);

        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));
        given(speciesRepository.findById(NEW_SPECIES_ID))
                .willReturn(Optional.of(species(NEW_SPECIES_ID, "스킨답서스", false)));

        productService.updateProduct(SELLER_ID,PRODUCT_ID,updateRequest(null));
        assertThat(product.getPictures()).isEmpty();
    }

    @Test
    @DisplayName("없는 상품을 수정하면 PRODUCT_NOT_FOUND")
    void updateProductNotFound() {
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> productService.updateProduct(SELLER_ID, PRODUCT_ID, updateRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
    }

    // 삭제된 상품은 없는 상품과 같이 취급
    @Test
    @DisplayName("삭제된 상품을 수정하면 PRODUCT_NOT_FOUND")
    void updateDeletedProduct() {
        given(productRepository.findWithLockById(PRODUCT_ID))
                .willReturn(Optional.of(product(ProductStatus.DELETED)));

        assertThatThrownBy(() -> productService.updateProduct(SELLER_ID, PRODUCT_ID, updateRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("본인 상품이 아니면 NOT_PRODUCT_OWNER, 품종도 조회하지 않음")
    void updateProductByOtherMember() {
        given(productRepository.findWithLockById(PRODUCT_ID))
                .willReturn(Optional.of(product(ProductStatus.ACTIVE)));

        assertThatThrownBy(() -> productService.updateProduct(OTHER_MEMBER_ID, PRODUCT_ID, updateRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.NOT_PRODUCT_OWNER);
        verify(speciesRepository, never()).findById(any());
    }

    @Test
    @DisplayName("없는 품종으로 수정하면 SPECIES_NOT_FOUND")
    void updateProductWithUnknownSpecies() {
        given(productRepository.findWithLockById(PRODUCT_ID))
                .willReturn(Optional.of(product(ProductStatus.ACTIVE)));
        given(speciesRepository.findById(NEW_SPECIES_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> productService.updateProduct(SELLER_ID, PRODUCT_ID, updateRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.SPECIES_NOT_FOUND);
    }
    @Test
    @DisplayName("금지 품종으로 수정하면 BANNED_SPECIES, 상품은 바뀌지 않음")
    void updateProductWithBannedSpecies() {
        Product product = product(ProductStatus.ACTIVE);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));
        given(speciesRepository.findById(NEW_SPECIES_ID))
                .willReturn(Optional.of(species(NEW_SPECIES_ID, "금지 품종", true)));

        assertThatThrownBy(() -> productService.updateProduct(SELLER_ID, PRODUCT_ID, updateRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.BANNED_SPECIES);
        assertThat(product.getTitle()).isEqualTo("몬스테라 분양합니다");
    }

    // 결제 대기·판매 완료 상품은 수정 불가 (정책 2.3-1)
    @Test
    @DisplayName("판매 중이 아닌 상품을 수정하면 PRODUCT_NOT_EDITABLE, 상품은 바뀌지 않음")
    void updateProductNotEditable() {
        Product product = product(ProductStatus.PAYMENT_PENDING);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));
        given(speciesRepository.findById(NEW_SPECIES_ID))
                .willReturn(Optional.of(species(NEW_SPECIES_ID, "스킨답서스", false)));

        assertThatThrownBy(() -> productService.updateProduct(SELLER_ID, PRODUCT_ID, updateRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_EDITABLE);
        assertThat(product.getTitle()).isEqualTo("몬스테라 분양합니다");
    }

    // ── 삭제 ───────────────────────────────────────

    @Test
    @DisplayName("상품을 삭제하면 데이터는 남고 상태만 DELETED로 바뀜")
    void deleteProduct() {
        Product product = product(ProductStatus.ACTIVE);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));

        productService.deleteProduct(SELLER_ID, PRODUCT_ID);

        assertThat(product.getStatus()).isEqualTo(ProductStatus.DELETED);
    }

    @Test
    @DisplayName("판매 중이 아닌 상품을 삭제하면 PRODUCT_NOT_DELETABLE")
    void deleteProductNotDeletable() {
        Product product = product(ProductStatus.PAYMENT_PENDING);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.deleteProduct(SELLER_ID, PRODUCT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_DELETABLE);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.PAYMENT_PENDING);
    }

    @Test
    @DisplayName("본인 상품이 아니면 삭제할 수 없음")
    void deleteProductByOtherMember() {
        Product product = product(ProductStatus.ACTIVE);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.deleteProduct(OTHER_MEMBER_ID, PRODUCT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.NOT_PRODUCT_OWNER);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    // ── 숨김 ───────────────────────────────────────

    @Test
    @DisplayName("판매 중인 상품을 숨길 수 있음")
    void hideActiveProduct() {
        Product product = product(ProductStatus.ACTIVE);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));

        productService.changeHidden(SELLER_ID, PRODUCT_ID, true);

        assertThat(product.isHidden()).isTrue();
    }

    // 수정·삭제와 달리 판매 완료 상품도 숨길 수 있음 (정책 2.3-3)
    @Test
    @DisplayName("판매 완료된 상품도 숨길 수 있음")
    void hideSoldOutProduct() {
        Product product = product(ProductStatus.SOLD_OUT);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));

        productService.changeHidden(SELLER_ID, PRODUCT_ID, true);

        assertThat(product.isHidden()).isTrue();
    }

    @Test
    @DisplayName("숨긴 상품의 숨김을 해제할 수 있음")
    void unhideProduct() {
        Product product = product(ProductStatus.ACTIVE);
        ReflectionTestUtils.setField(product, "hidden", true);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));

        productService.changeHidden(SELLER_ID, PRODUCT_ID, false);

        assertThat(product.isHidden()).isFalse();
    }

    @Test
    @DisplayName("결제 대기 중인 상품을 숨기면 PRODUCT_NOT_HIDEABLE")
    void hidePaymentPendingProduct() {
        Product product = product(ProductStatus.PAYMENT_PENDING);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.changeHidden(SELLER_ID, PRODUCT_ID, true))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_HIDEABLE);
        assertThat(product.isHidden()).isFalse();
    }

    // ── 상세 ───────────────────────────────────────

    // 숨긴 상품 (판매 중)
    private Product hiddenProduct() {
        Product product = product(ProductStatus.ACTIVE);
        ReflectionTestUtils.setField(product, "hidden", true);
        return product;
    }

    @Test
    @DisplayName("숨기지 않은 상품은 비회원도 상세를 조회할 수 있음")
    void getProductDetailByGuest() {
        given(productRepository.findWithDetailById(PRODUCT_ID))
                .willReturn(Optional.of(product(ProductStatus.ACTIVE)));

        ProductDetailResponse response = productService.getProductDetail(PRODUCT_ID, null);

        assertThat(response.productId()).isEqualTo(PRODUCT_ID);
        assertThat(response.hidden()).isFalse();
    }

    @Test
    @DisplayName("판매자 본인은 숨긴 상품의 상세를 조회할 수 있음")
    void getHiddenProductDetailByOwner() {
        given(productRepository.findWithDetailById(PRODUCT_ID)).willReturn(Optional.of(hiddenProduct()));

        ProductDetailResponse response = productService.getProductDetail(PRODUCT_ID, SELLER_ID);

        assertThat(response.productId()).isEqualTo(PRODUCT_ID);
        assertThat(response.hidden()).isTrue();
    }

    @Test
    @DisplayName("다른 회원은 숨긴 상품의 상세를 조회하면 PRODUCT_NOT_FOUND")
    void getHiddenProductDetailByOtherMember() {
        given(productRepository.findWithDetailById(PRODUCT_ID)).willReturn(Optional.of(hiddenProduct()));

        assertThatThrownBy(() -> productService.getProductDetail(PRODUCT_ID, OTHER_MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("비회원은 숨긴 상품의 상세를 조회하면 PRODUCT_NOT_FOUND")
    void getHiddenProductDetailByGuest() {
        given(productRepository.findWithDetailById(PRODUCT_ID)).willReturn(Optional.of(hiddenProduct()));

        assertThatThrownBy(() -> productService.getProductDetail(PRODUCT_ID, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
    }

    // 삭제된 상품은 본인에게도 보이지 않음
    @Test
    @DisplayName("판매자 본인도 삭제된 상품의 상세를 조회하면 PRODUCT_NOT_FOUND")
    void getDeletedProductDetailByOwner() {
        given(productRepository.findWithDetailById(PRODUCT_ID))
                .willReturn(Optional.of(product(ProductStatus.DELETED)));

        assertThatThrownBy(() -> productService.getProductDetail(PRODUCT_ID, SELLER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
    }
}
