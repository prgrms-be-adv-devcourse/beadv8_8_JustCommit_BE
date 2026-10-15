package com.justcommit.backend.product.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.domain.exception.ProductErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

class ProductImageUrlValidatorTest {

    private static final String BASE_URL = "https://bucket.example.com";
    private final ProductImageUrlValidator validator = new ProductImageUrlValidator(BASE_URL);


    @Test
    @DisplayName("판매자 본인 경로의 이미지는 통과")
    void validUrl(){
        assertThatCode(()->validator.validate(1L, List.of(BASE_URL+"/products/1/a.jpg")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("사진이 없으면 통과")
    void emptyUrls() {
        assertThatCode(() -> validator.validate(1L, List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("외부 URL 이면 INVALID_IMAGE_URL")
    void externalUrl() {
        assertInvalid(1L, "https://other.com/products/1/a.jpg");
    }

    @Test
    @DisplayName("다른 판매자 경로의 이미지면 INVALID_IMAGE_URL")
    void otherSellerUrl() {
        assertInvalid(1L, BASE_URL + "/products/2/a.jpg");
    }

    @Test
    @DisplayName("판매자 ID 앞부분만 같은 경로면 INVALID_IMAGE_URL")
    void similarSellerIdUrl() {
        assertInvalid(1L, BASE_URL + "/products/12/a.jpg");
    }


    @Test
    @DisplayName("경로에 ..이 들어가면 INVALID_IMAGE_URL")
    void pathTraversalUrl() {
        assertInvalid(1L, BASE_URL + "/products/1/../2/a.jpg");
    }


    @Test
    @DisplayName("기본 URL 끝에 '/'가 있어도 판매자 본인 경로의 이미지는 통과")
    void baseUrlWithTrailingSlash() {
        ProductImageUrlValidator slashValidator = new ProductImageUrlValidator(BASE_URL + "/");

        assertThatCode(() -> slashValidator.validate(1L, List.of(BASE_URL + "/products/1/a.jpg")))
                .doesNotThrowAnyException();
    }


    private void assertInvalid(Long sellerId, String url) {
        assertThatThrownBy(() -> validator.validate(sellerId, List.of(url)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.INVALID_IMAGE_URL);
    }

}