package com.justcommit.backend.product.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberQuery;
import com.justcommit.backend.product.domain.exception.ProductErrorCode;
import com.justcommit.backend.product.presentation.dto.ImageUploadUrlRequest;
import com.justcommit.backend.product.presentation.dto.ImageUploadUrlRequest.ImageFile;
import com.justcommit.backend.product.presentation.dto.ImageUploadUrlResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ProductImageServiceTest {

    private static final String BASE_URL = "https://bucket.example.com";
    private static final Long SELLER_ID = 7L;
    private static final long MAX_SIZE = 10L * 1024 * 1024;

    @Mock
    private ImageUploadUrlIssuer uploadUrlIssuer;

    @Mock
    private MemberQuery memberQuery;

    private ProductImageService productImageService;

    @BeforeEach
    void setUp() {
        productImageService = new ProductImageService(uploadUrlIssuer, memberQuery, BASE_URL);
    }

    private ImageUploadUrlRequest request(ImageUploadUrlRequest.ImageFile... files) {
        return new ImageUploadUrlRequest(List.of(files));
    }

    @Test
    @DisplayName("판매자면 파일마다 업로드 링크와 판매자 경로의 최종 이미지 URL을 발급")
    void issueUploadUrls() {
        //given
        given(memberQuery.isSeller(SELLER_ID)).willReturn(true);
        given(uploadUrlIssuer.issue(anyString(), anyString(), anyLong())).willReturn("https://signed-url");

        //when
        List<ImageUploadUrlResponse> result = productImageService.issueUploadUrls(SELLER_ID,
                request(new ImageFile("image/jpeg", 2_000L), new ImageFile("image/png", 1_000L)));

        //then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).uploadUrl()).isEqualTo("https://signed-url");
        assertThat(result.get(0).imageUrl()).matches(BASE_URL + "/products/7/[0-9a-f-]{36}\\.jpg");
        assertThat(result.get(1).imageUrl()).matches(BASE_URL + "/products/7/[0-9a-f-]{36}\\.png");
        assertThat(result.get(0).imageUrl()).isNotEqualTo(result.get(1).imageUrl());   // UUID로 이름이 겹치지 않음
    }

    @Test
    @DisplayName("발급기에는 최종 URL과 같은 키, 요청한 형식·크기를 넘김")
    void passesKeyContentTypeAndSize() {
        given(memberQuery.isSeller(SELLER_ID)).willReturn(true);
        given(uploadUrlIssuer.issue(anyString(), anyString(), anyLong())).willReturn("https://signed-url");

        ImageUploadUrlResponse response = productImageService.issueUploadUrls(SELLER_ID,
                request(new ImageFile("image/webp", 3_000L))).get(0);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(uploadUrlIssuer).issue(key.capture(), eq("image/webp"), eq(3_000L));
        assertThat(response.imageUrl()).isEqualTo(BASE_URL + "/" + key.getValue());
    }


    @Test
    @DisplayName("기본 URL 끝에 '/'가 있어도 최종 URL에 슬래시가 두 번 들어가지 않음")
    void baseUrlWithTrailingSlash() {
        ProductImageService service = new ProductImageService(uploadUrlIssuer, memberQuery, BASE_URL + "/");
        given(memberQuery.isSeller(SELLER_ID)).willReturn(true);
        given(uploadUrlIssuer.issue(anyString(), anyString(), anyLong())).willReturn("https://signed-url");

        String imageUrl = service.issueUploadUrls(SELLER_ID, request(new ImageFile("image/jpeg", 1_000L)))
                .get(0).imageUrl();

        assertThat(imageUrl).startsWith(BASE_URL + "/products/7/");
    }

    @Test
    @DisplayName("판매자가 아니면 NOT_SELLER, 링크를 발급하지 않음")
    void notSeller() {
        given(memberQuery.isSeller(SELLER_ID)).willReturn(false);

        assertThatThrownBy(() -> productImageService.issueUploadUrls(SELLER_ID,
                request(new ImageFile("image/jpeg", 1_000L))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.NOT_SELLER);

        verify(uploadUrlIssuer, never()).issue(any(), any(), anyLong());
    }

    @Test
    @DisplayName("허용하지 않는 형식이면 INVALID_IMAGE_FILE")
    void unsupportedContentType() {
        given(memberQuery.isSeller(SELLER_ID)).willReturn(true);

        assertInvalidFile(new ImageFile("image/gif", 1_000L));
    }

    @Test
    @DisplayName("10MB를 넘으면 INVALID_IMAGE_FILE")
    void tooLarge() {
        given(memberQuery.isSeller(SELLER_ID)).willReturn(true);

        assertInvalidFile(new ImageFile("image/jpeg", MAX_SIZE + 1));
    }

    // 크기가 정확히 10 MB 인 경우->경계가 정확하게 지켜지는지 확인하기 위함. 경계에서 버그가 많이 생겨서 경계 테스트 진행
    @Test
    @DisplayName("정확히 10MB는 허용")
    void exactlyMaxSize() {
        given(memberQuery.isSeller(SELLER_ID)).willReturn(true);
        given(uploadUrlIssuer.issue(anyString(), anyString(), anyLong())).willReturn("https://signed-url");

        assertThat(productImageService.issueUploadUrls(SELLER_ID,
                request(new ImageFile("image/jpeg", MAX_SIZE)))).hasSize(1);
    }

    private void assertInvalidFile(ImageFile file) {
        assertThatThrownBy(() -> productImageService.issueUploadUrls(SELLER_ID, request(file)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.INVALID_IMAGE_FILE);

        verify(uploadUrlIssuer, never()).issue(any(), any(), anyLong());
    }

}
