package com.justcommit.backend.product.product.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberQuery;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.image.application.ProductImageCleaner;
import com.justcommit.backend.product.image.application.ProductImageUrlValidator;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.domain.ProductPicture;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.product.presentation.dto.*;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class) //JUnit - Mockito 연결
public class  ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private SpeciesRepository speciesRepository;

    @Mock
    private MemberQuery memberQuery;

    @Mock
    private ProductImageCleaner imageCleaner;

    @Mock
    private ProductImageUrlValidator imageUrlValidator;

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

    private ProductRepository.StatusCount statusCount(ProductStatus status, long count) {
        return new ProductRepository.StatusCount() {
            @Override public ProductStatus getStatus() { return status; }
            @Override public Long getCount() { return count; }
        };
    }

    // ── 등록 ───────────────────────────────────────

    // 등록 요청
    private ProductRequest createRequest(){
        return new ProductRequest(1L, "몬스테라 분양합니다", "잘 자란 몬스테라입니다",
                new BigDecimal("30000"), 50, "중형", null, List.of("https://example.com/a.jpg"));
    }

    @Test
    @DisplayName("판매자로 등록되지 않은 회원이 상품을 등록하면 NOT_SELLER, 품종 조회·저장을 하지 않음")
    void createProductByNotSeller(){
        given(memberQuery.isSeller(SELLER_ID)).willReturn(false);

        assertThatThrownBy(() -> productService.createProduct(SELLER_ID, createRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.NOT_SELLER);

        verify(speciesRepository, never()).findById(any()); //한 번도 호출되지 않았는지 확인. 판매자가 아닌 경우 품종 조회, 저장 안가야함
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("판매자면 상품이 생성되고, 반환시 닉네임이 잘 담기는 확인")
    void createProduct() {

        //given 1) 회원 1번이 판매자? -> true 2)품종 1번 -> 품종 1번에 대한 정보 3)저장시 -> 저장 객체 반환
        given(memberQuery.isSeller(SELLER_ID)).willReturn(true);
        given(speciesRepository.findById(1L)).willReturn(Optional.of(species(1L, "몬스테라", false)));
        given(productRepository.save(any(Product.class))).willAnswer(invocation -> invocation.getArgument(0)); //
        given(memberQuery.getNickname(SELLER_ID)).willReturn("판매자1");

        //when - 서비스 메소드 호출 . 위의 내용으로 상품 등록
        ProductDetailResponse response = productService.createProduct(SELLER_ID, createRequest());

        //then - 생성된 응답에 제대로 값이 드어갔는지 확인
        assertThat(response.sellerName()).isEqualTo("판매자1");
        assertThat(response.title()).isEqualTo("몬스테라 분양합니다");
    }

    @Test
    @DisplayName("이미지 검증에 실패하면 상품을 저장하지 않음")
    void createProductWithInvalidImage() {
        given(memberQuery.isSeller(SELLER_ID)).willReturn(true);
        //예외를 일으키는 상황을 만들기 - validate 호출시 해당 예외 던지는지 확인
        willThrow(new BusinessException(ProductErrorCode.INVALID_IMAGE_URL))
                .given(imageUrlValidator).validate(eq(SELLER_ID), any());

        // 예외가 서비스 밖으로 잘 나왔는지 확인 : createProduct 시 예외가 나야하고, 해당하는 예외가 나지 않으면 실패
        assertThatThrownBy(() -> productService.createProduct(SELLER_ID, createRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.INVALID_IMAGE_URL);
        // 예외 후 저장까지 가지 않았는지 확인
        verify(productRepository, never()).save(any());
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
    @DisplayName("결제 대기 중인 상품도 숨길 수 있음")
    void hidePaymentPendingProduct() {
        Product product = product(ProductStatus.PAYMENT_PENDING);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));

        productService.changeHidden(SELLER_ID, PRODUCT_ID, true);

        assertThat(product.isHidden()).isTrue();
    }

    @Test
    @DisplayName("삭제된 상품의 숨김을 바꾸려 하면 PRODUCT_NOT_FOUND")
    void hideDeletedProduct() {
        Product product = product(ProductStatus.DELETED);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.changeHidden(SELLER_ID, PRODUCT_ID, true))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
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
    @DisplayName("상품 상세에 판매자 닉네임을 담아 반환")
    void getProductDetailWithSellerName() {

        //given
        Product product = product(ProductStatus.ACTIVE);

        // 10번(PRODUCT_ID) 상품 조회시 위에서 만든 상품 return
        given(productRepository.findWithDetailById(PRODUCT_ID)).willReturn(Optional.of(product));
        // 회원 1(SELLER_ID)  조회시 판매자 1 응답
        given(memberQuery.getNickname(SELLER_ID)).willReturn("판매자1");


        //when : 서비스 호출  - 비로그인 조회라서 null 을 넣음
        ProductDetailResponse response = productService.getProductDetail(PRODUCT_ID, null);

        //then : 응답확인 - sellerName 이 일치하는지
        assertThat(response.sellerName()).isEqualTo("판매자1");
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



    /*내 판매 목록*/
    @Test
    @DisplayName("내 판매 목록은 최신순/기본 페이지로 조회하고 숨김 여부를 응답에 담음")
    void getMyProduct(){
        Product product = product(ProductStatus.ACTIVE);
        ReflectionTestUtils.setField(product, "hidden",true);

        given(productRepository.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(product)));

        Page<MyProductResponse> result = productService.getMyProducts(SELLER_ID, new MyProductSearchRequest(null,null, null, null));

        //서비스가 repo 에 넘김 Pageable 확인
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findAll(any(Specification.class), pageable.capture());

        assertThat(pageable.getValue().getPageNumber()).isZero(); //page를 null로 넘겼을 때 toPageable()이 기본값 0으로 채웠는지 검증
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));

        MyProductResponse item = result.getContent().get(0);
        // Page<MyProductResponse> 의 데이터 목록인 List<MyProductResponse> 중 첫번째 항목

        assertThat(item.productId()).isEqualTo(PRODUCT_ID);
        assertThat(item.hidden()).isTrue(); //hidden: true가 제대로 담겼는지 검증

    }

    @Test
    @DisplayName("상품을 수정하면 새 목록에서 빠진 기존 사진만 삭제 예약")
    void updateProductDeletesRemovedPictures() {
        Product product = product(ProductStatus.ACTIVE);   // 기존 사진: a.jpg
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));
        given(speciesRepository.findById(NEW_SPECIES_ID))
                .willReturn(Optional.of(species(NEW_SPECIES_ID, "스킨답서스", false)));

        productService.updateProduct(SELLER_ID, PRODUCT_ID,
                updateRequest(List.of("https://example.com/c.jpg")));

        verify(imageCleaner).deleteAfterCommit(List.of("https://example.com/a.jpg"));
    }

    @Test
    @DisplayName("기존 사진을 그대로 두고 수정하면 삭제할 사진이 없음")
    void updateProductKeepsPictures() {
        Product product = product(ProductStatus.ACTIVE);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));
        given(speciesRepository.findById(NEW_SPECIES_ID))
                .willReturn(Optional.of(species(NEW_SPECIES_ID, "스킨답서스", false)));

        productService.updateProduct(SELLER_ID, PRODUCT_ID,
                updateRequest(List.of("https://example.com/a.jpg", "https://example.com/c.jpg")));

        verify(imageCleaner).deleteAfterCommit(List.of());
    }

    @Test
    @DisplayName("상품을 삭제하면 사진 전부 삭제 예약")
    void deleteProductDeletesAllPictures() {
        Product product = product(ProductStatus.ACTIVE);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));

        productService.deleteProduct(SELLER_ID, PRODUCT_ID);

        verify(imageCleaner).deleteAfterCommit(List.of("https://example.com/a.jpg"));
    }

    @Test
    @DisplayName("판매 중이 아니라 수정에 실패하면 사진을 삭제하지 않음")
    void updateProductFailsDoesNotDeletePictures() {
        Product product = product(ProductStatus.SOLD_OUT);
        given(productRepository.findWithLockById(PRODUCT_ID)).willReturn(Optional.of(product));
        given(speciesRepository.findById(NEW_SPECIES_ID))
                .willReturn(Optional.of(species(NEW_SPECIES_ID, "스킨답서스", false)));

        assertThatThrownBy(() -> productService.updateProduct(SELLER_ID, PRODUCT_ID,
                updateRequest(List.of("https://example.com/c.jpg"))))
                .isInstanceOf(BusinessException.class);

        verify(imageCleaner, never()).deleteAfterCommit(any());
    }

    @Test
    @DisplayName("상태별 개수를 탭 개수로 변환하고 없는 상태는 0, 전체는 합계")
    void getMyProductCounts() {
        given(productRepository.countByStatus(SELLER_ID, null))
                .willReturn(List.of(statusCount(ProductStatus.ACTIVE, 2), statusCount(ProductStatus.SOLD_OUT, 1)));

        MyProductCountResponse result = productService.getMyProductCounts(SELLER_ID, null);

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.active()).isEqualTo(2);
        assertThat(result.paymentPending()).isZero();
        assertThat(result.soldOut()).isEqualTo(1);
    }

}
