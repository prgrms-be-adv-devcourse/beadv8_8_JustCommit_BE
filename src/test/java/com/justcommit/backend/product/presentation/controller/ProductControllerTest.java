package com.justcommit.backend.product.presentation.controller;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.application.ProductImageService;
import com.justcommit.backend.product.application.ProductService;
import com.justcommit.backend.product.domain.exception.ProductErrorCode;
import com.justcommit.backend.product.presentation.dto.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    private static final Long SELLER_ID = 1L;
    private static final String SELLER = "SELLER";
    private static final String MEMBER = "MEMBER";

    // 등록 요청
    private static final String VALID_CREATE_BODY = """
            {
              "speciesId": 1,
              "title": "몬스테라 분양합니다",
              "description": "잘 자란 몬스테라입니다",
              "price": 30000,
              "heightCm": 50,
              "potSize": "중형",
              "growNote": "베란다에서 키웠어요",
              "pictureUrls": ["https://example.com/a.jpg", "https://example.com/b.jpg"]
            }
            """;

    // 등록 요청 (사진 없음, 필수 항목만)
    private static final String NO_PICTURE_CREATE_BODY = """
            {
              "speciesId": 1,
              "title": "몬스테라 분양합니다",
              "description": "잘 자란 몬스테라입니다",
              "price": 30000
            }
            """;

    @Mock
    private ProductService productService;

    @Mock
    ProductImageService productImageService;

    private MockMvc mockMvc;  // HTTP 요청을 흉내 내서 Controller 호출

    // 테스트마다 새로운 MockMvc 생성
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(productImageService, productService))   // Controller 하나만 등록
                .setControllerAdvice(new GlobalExceptionHandler())
                // @AuthenticationPrincipal AuthMember를 SecurityContext에서 꺼내 주는 처리기
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }
    // 테스트 끝나면 로그인 지우기
    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    // 테스트에서 로그인 상태 만들기
    private void loginAs(Long memberId, String role) {
        AuthMember authMember = new AuthMember(memberId, role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(authMember, null, List.of()));
    }

    // DetailResponse 가짜 응답 데이터
    private ProductDetailResponse detailResponse() {
        return new ProductDetailResponse(
                10L, SELLER_ID, "Seller01","몬스테라 분양합니다", "잘 자란 몬스테라입니다",
                new BigDecimal("30000"), 50, "중형", "베란다에서 키웠어요", "ACTIVE", false,
                new ProductSpeciesResponse(1L, "몬스테라", "FOLIAGE", "MEDIUM", "WHEN_SURFACE_DRY", "EASY"),
                List.of("https://example.com/a.jpg", "https://example.com/b.jpg"),
                LocalDateTime.now(), LocalDateTime.now());
    }

    // ── 등록 ───────────────────────────────────────

    @Test
    @DisplayName("상품을 등록하면 201과 등록된 상품 상세를 반환")
    void createProduct() throws Exception {
        loginAs(SELLER_ID, SELLER);
        given(productService.createProduct(eq(SELLER_ID), any())).willReturn(detailResponse());

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.productId").value(10))
                .andExpect(jsonPath("$.data.sellerId").value(SELLER_ID))
                .andExpect(jsonPath("$.data.species.name").value("몬스테라"))
                .andExpect(jsonPath("$.data.imageUrls[0]").value("https://example.com/a.jpg"));
    }

    // 사진 없이 등록 -> 201
    @Test
    @DisplayName("사진 없이도 상품을 등록할 수 있음")
    void createProductWithoutPictures() throws Exception {
        loginAs(SELLER_ID, SELLER);
        given(productService.createProduct(eq(SELLER_ID), any())).willReturn(detailResponse());

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NO_PICTURE_CREATE_BODY))
                .andExpect(status().isCreated());
    }

    // 판매자가 아닌 회원 -> 403
    @Test
    @DisplayName("판매자가 아닌 회원이 등록하면 403 NOT_SELLER")
    void createProductByMember() throws Exception {
        loginAs(SELLER_ID, MEMBER);
        given(productService.createProduct(eq(SELLER_ID), any()))
                .willThrow(new BusinessException(ProductErrorCode.NOT_SELLER));


        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_SELLER"));
    }

    // 제목 공백 -> 400
    @Test
    @DisplayName("제목이 비어 있으면 400 INVALID_FIELD_ERROR")
    void createProductWithBlankTitle() throws Exception {
        loginAs(SELLER_ID, SELLER);
        String body = VALID_CREATE_BODY.replace("\"몬스테라 분양합니다\"", "\"  \"");

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
    }

    // 사진 11장 -> 400
    @Test
    @DisplayName("사진이 10장을 넘으면 400 INVALID_FIELD_ERROR")
    void createProductWithTooManyPictures() throws Exception {
        loginAs(SELLER_ID, SELLER);
        String urls = String.join(",", Collections.nCopies(11, "\"https://example.com/a.jpg\""));
        String body = VALID_CREATE_BODY.replace(
                "[\"https://example.com/a.jpg\", \"https://example.com/b.jpg\"]", "[" + urls + "]");

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
    }

    // 금지 품종 -> 400
    @Test
    @DisplayName("금지 품종으로 등록하면 400 BANNED_SPECIES")
    void createProductWithBannedSpecies() throws Exception {
        loginAs(SELLER_ID, SELLER);
        given(productService.createProduct(eq(SELLER_ID), any()))
                .willThrow(new BusinessException(ProductErrorCode.BANNED_SPECIES));

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANNED_SPECIES"));
    }

    // 없는 품종 -> 404
    @Test
    @DisplayName("없는 품종으로 등록하면 404 SPECIES_NOT_FOUND")
    void createProductWithUnknownSpecies() throws Exception {
        loginAs(SELLER_ID, SELLER);
        given(productService.createProduct(eq(SELLER_ID), any()))
                .willThrow(new BusinessException(ProductErrorCode.SPECIES_NOT_FOUND));

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SPECIES_NOT_FOUND"));
    }

    // ── 목록 ───────────────────────────────────────

    @Test
    @DisplayName("상품 목록을 조회하면 공통 응답 형식과 페이지 정보를 반환")
    void searchProducts() throws Exception {
        ProductSearchResponse item = new ProductSearchResponse(
                10L, "몬스테라 분양합니다", new BigDecimal("30000"), "https://example.com/a.jpg",
                1L, "몬스테라", "FOLIAGE", "ACTIVE", LocalDateTime.now());
        given(productService.searchProducts(any()))
                .willReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/products").param("light", "LOW").param("sort", "priceAsc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].productId").value(10))
                .andExpect(jsonPath("$.data.content[0].speciesName").value("몬스테라"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    // 잘못된 Light 값 -> 400
    @Test
    @DisplayName("허용되지 않는 광량 값으로 검색하면 400 INVALID_FIELD_ERROR")
    void searchProductsWithInvalidLight() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("light", "WRONG"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
    }

    // ── 상세 ───────────────────────────────────────


    @Test
    @DisplayName("상품 상세를 조회하면 품종 정보와 사진 목록을 반환")
    void getProductDetail() throws Exception {
        given(productService.getProductDetail(10L, null)).willReturn(detailResponse());

        mockMvc.perform(get("/api/v1/products/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellerName").value("Seller01"))
                .andExpect(jsonPath("$.data.title").value("몬스테라 분양합니다"))
                .andExpect(jsonPath("$.data.species.category").value("FOLIAGE"))
                .andExpect(jsonPath("$.data.imageUrls.length()").value(2));
    }

    // 로그인한 경우 숨긴 상품 판단을 위해 회원 ID를 서비스로 넘김
    @Test
    @DisplayName("로그인한 회원이 상세를 조회하면 회원 ID를 함께 넘김")
    void getProductDetailWithLogin() throws Exception {
        loginAs(SELLER_ID, SELLER);
        given(productService.getProductDetail(10L, SELLER_ID)).willReturn(detailResponse());

        mockMvc.perform(get("/api/v1/products/10"))
                .andExpect(status().isOk());
    }

    // 없는 상품 -> 404
    @Test
    @DisplayName("없는 상품을 조회하면 404 PRODUCT_NOT_FOUND")
    void getProductDetailNotFound() throws Exception {
        given(productService.getProductDetail(999L, null))
                .willThrow(new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/products/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }


    // ── 수정 ───────────────────────────────────────

    @Test
    @DisplayName("상품을 수정하면 200과 수정된 상품 상세를 반환")
    void updateProduct() throws Exception {
        loginAs(SELLER_ID, SELLER);
        given(productService.updateProduct(eq(SELLER_ID), eq(10L), any())).willReturn(detailResponse());

        mockMvc.perform(put("/api/v1/products/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.productId").value(10));

        verify(productService).updateProduct(eq(SELLER_ID), eq(10L), any());
    }

    // 다른 판매자의 상품 -> 403
    @Test
    @DisplayName("본인 상품이 아니면 403 NOT_PRODUCT_OWNER")
    void updateProductByOtherSeller() throws Exception {
        loginAs(SELLER_ID, SELLER);
        given(productService.updateProduct(eq(SELLER_ID), eq(10L), any()))
                .willThrow(new BusinessException(ProductErrorCode.NOT_PRODUCT_OWNER));

        mockMvc.perform(put("/api/v1/products/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_PRODUCT_OWNER"));
    }

    @Test
    @DisplayName("판매 중이 아닌 상품을 수정하면 409 PRODUCT_NOT_EDITABLE")
    void updateProductNotEditable() throws Exception{
        loginAs(SELLER_ID, SELLER);
        given(productService.updateProduct(eq(SELLER_ID), eq(10L), any()))
                .willThrow(new BusinessException(ProductErrorCode.PRODUCT_NOT_EDITABLE));

        mockMvc.perform(put("/api/v1/products/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_EDITABLE"));
    }

    // 없거나 삭제된 상품 -> 404
    @Test
    @DisplayName("없거나 삭제된 상품을 수정하면 404 PRODUCT_NOT_FOUND")
    void updateProductNotFound() throws Exception {
        loginAs(SELLER_ID, SELLER);
        given(productService.updateProduct(eq(SELLER_ID), eq(999L), any()))
                .willThrow(new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));

        mockMvc.perform(put("/api/v1/products/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    // 제목 공백 -> 400
    @Test
    @DisplayName("수정 시 제목이 비어 있으면 400 INVALID_FIELD_ERROR")
    void updateProductWithBlankTitle() throws Exception {
        loginAs(SELLER_ID, SELLER);
        String body = VALID_CREATE_BODY.replace("\"몬스테라 분양합니다\"", "\"  \"");

        mockMvc.perform(put("/api/v1/products/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
    }

    // ── 삭제 ───────────────────────────────────────

    @Test
    @DisplayName("상품을 삭제하면 200")
    void deleteProduct() throws Exception {
        loginAs(SELLER_ID, SELLER);

        mockMvc.perform(delete("/api/v1/products/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("상품을 삭제했습니다."));

        verify(productService).deleteProduct(SELLER_ID, 10L);
    }

    @Test
    @DisplayName("판매 중이 아닌 상품을 삭제하면 409 PRODUCT_NOT_DELETABLE")
    void deleteProductNotDeletable() throws Exception {
        loginAs(SELLER_ID, SELLER);
        willThrow(new BusinessException(ProductErrorCode.PRODUCT_NOT_DELETABLE))
                .given(productService).deleteProduct(SELLER_ID, 10L);

        mockMvc.perform(delete("/api/v1/products/10"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_DELETABLE"));
    }

    // ── 숨김 ───────────────────────────────────────

    @Test
    @DisplayName("상품을 숨기면 200")
    void hideProduct() throws Exception {
        loginAs(SELLER_ID, SELLER);

        mockMvc.perform(patch("/api/v1/products/10/hidden")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("상품을 숨겼습니다."));

        verify(productService).changeHidden(SELLER_ID, 10L, true);
    }

    // hidden을 빠뜨리면 false로 처리되지 않고 400
    @Test
    @DisplayName("hidden 값이 없으면 400 INVALID_FIELD_ERROR")
    void changeHiddenWithoutValue() throws Exception {
        loginAs(SELLER_ID, SELLER);

        mockMvc.perform(patch("/api/v1/products/10/hidden")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
    }

    @Test
    @DisplayName("숨길 수 없는 상태면 409 PRODUCT_NOT_HIDEABLE")
    void hideProductNotHideable() throws Exception {
        loginAs(SELLER_ID, SELLER);
        willThrow(new BusinessException(ProductErrorCode.PRODUCT_NOT_HIDEABLE))
                .given(productService).changeHidden(SELLER_ID, 10L, true);

        mockMvc.perform(patch("/api/v1/products/10/hidden")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hidden\": true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_HIDEABLE"));
    }

    // ── 내 판매 목록 ───────────────────────────────

    //가짜 응답 데이터
    private MyProductResponse myProductResponse(boolean hidden)
    {
        return new MyProductResponse(10L, "몬스테라 분양합니다", new BigDecimal("30000"),
                "https://example.com/a.jpg", 1L, "몬스테라", "ACTIVE", hidden, LocalDateTime.now());
    }

    @Test
    @DisplayName("내 판매 목록을 조회하면 로그인한 회원 ID로 조회하고 숨김 여부와 페이지 정보를 반환")
    void getMyProducts() throws Exception {
        loginAs(SELLER_ID, SELLER);
        given(productService.getMyProducts(eq(SELLER_ID), any()))
                .willReturn(new PageImpl<>(List.of(myProductResponse(true)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/products/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].productId").value(10))
                .andExpect(jsonPath("$.data.content[0].hidden").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("상태/페이지 쿼리 파라미터를 요청 객체로 묶어 서비스에 넘김")
    void getMyProductsWithStatus() throws Exception {
        loginAs(SELLER_ID, SELLER);
        given(productService.getMyProducts(eq(SELLER_ID), any())).willReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/products/me")
                        .param("status", "SOLD_OUT").param("page", "1").param("size", "10"))
                .andExpect(status().isOk());

        ArgumentCaptor<MyProductSearchRequest> captor = ArgumentCaptor.forClass(MyProductSearchRequest.class);
        verify(productService).getMyProducts(eq(SELLER_ID), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ProductStatus.SOLD_OUT);
        assertThat(captor.getValue().page()).isEqualTo(1);
        assertThat(captor.getValue().size()).isEqualTo(10);
    }

    // 잘못된 상태 값 -> 400
    @Test
    @DisplayName("허용되지 않는 상태 값으로 조회하면 400 INVALID_FIELD_ERROR")
    void getMyProductsWithInvalidStatus() throws Exception {
        loginAs(SELLER_ID, SELLER);

        mockMvc.perform(get("/api/v1/products/me").param("status", "WRONG"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
    }
    // ── 이미지 업로드 링크 ─────────────────────────

    @Test
    @DisplayName("업로드 링크를 발급하면 로그인 회원 ID로 요청하고 링크 목록을 반환")
    void issueImageUploadUrls() throws Exception {
        loginAs(SELLER_ID, SELLER);
        given(productImageService.issueUploadUrls(eq(SELLER_ID), any()))
                .willReturn(List.of(new ImageUploadUrlResponse("https://signed-url", "https://bucket/products/1/a.jpg")));

        mockMvc.perform(post("/api/v1/products/images/upload-urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "files": [ { "contentType": "image/jpeg", "size": 1000 } ] }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].uploadUrl").value("https://signed-url"))
                .andExpect(jsonPath("$.data[0].imageUrl").value("https://bucket/products/1/a.jpg"));
    }

    @Test
    @DisplayName("파일 목록이 비어 있으면 400 INVALID_FIELD_ERROR")
    void issueImageUploadUrlsWithEmptyFiles() throws Exception {
        loginAs(SELLER_ID, SELLER);

        mockMvc.perform(post("/api/v1/products/images/upload-urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "files": [] }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
    }

    @Test
    @DisplayName("파일 크기가 0이면 400 INVALID_FIELD_ERROR")
    void issueImageUploadUrlsWithZeroSize() throws Exception {
        loginAs(SELLER_ID, SELLER);

        mockMvc.perform(post("/api/v1/products/images/upload-urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "files": [ { "contentType": "image/jpeg", "size": 0 } ] }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
    }

}