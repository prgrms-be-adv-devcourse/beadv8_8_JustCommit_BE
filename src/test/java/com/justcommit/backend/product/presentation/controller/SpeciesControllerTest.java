package com.justcommit.backend.product.presentation.controller;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.product.application.SpeciesService;
import com.justcommit.backend.product.domain.exception.ProductErrorCode;
import com.justcommit.backend.product.presentation.dto.SpeciesDetailResponse;
import com.justcommit.backend.product.presentation.dto.SpeciesSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SpeciesControllerTest {

    @Mock
    private SpeciesService speciesService;

    private MockMvc mockMvc;

    // Spring 없이 Controller와 공통 예외 처리만 연결
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SpeciesController(speciesService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("품종 목록을 조회하면 공통 응답 형식과 페이지 정보를 반환")
    void searchSpecies() throws Exception {
        SpeciesSearchResponse monstera =
                new SpeciesSearchResponse(1L, "몬스테라", "FOLIAGE", "https://example.com/monstera.jpg");
        given(speciesService.searchSpecies(any()))
                .willReturn(new PageImpl<>(List.of(monstera), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/species"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].speciesId").value(1))
                .andExpect(jsonPath("$.data.content[0].name").value("몬스테라"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.page").value(0));
    }

    @Test
    @DisplayName("품종 상세를 조회하면 사진 목록을 포함해 반환")
    void getSpeciesDetail() throws Exception {
        SpeciesDetailResponse detail = new SpeciesDetailResponse(
                1L, "몬스테라", "FOLIAGE", "MEDIUM", "WHEN_SURFACE_DRY", "EASY", "GREEN", false,
                List.of(new SpeciesDetailResponse.PictureResponse("https://example.com/monstera.jpg", 0)));
        given(speciesService.getSpeciesDetail(1L)).willReturn(detail);

        mockMvc.perform(get("/api/v1/species/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("몬스테라"))
                .andExpect(jsonPath("$.data.isBanned").value(false))
                .andExpect(jsonPath("$.data.pictures[0].sortOrder").value(0));
    }

    @Test
    @DisplayName("존재하지 않는 품종을 조회하면 404 SPECIES_NOT_FOUND")
    void speciesNotFound() throws Exception {
        given(speciesService.getSpeciesDetail(99999L))
                .willThrow(new BusinessException(ProductErrorCode.SPECIES_NOT_FOUND));

        mockMvc.perform(get("/api/v1/species/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SPECIES_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("허용되지 않는 카테고리로 검색하면 400 INVALID_FIELD_ERROR")
    void invalidCategory() throws Exception {
        mockMvc.perform(get("/api/v1/species").param("category", "WRONG"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"));
    }

    @Test
    @DisplayName("품종 ID가 숫자가 아니면 400 TYPE_MISMATCH")
    void invalidSpeciesId() throws Exception {
        mockMvc.perform(get("/api/v1/species/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"));
    }
}