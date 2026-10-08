package com.justcommit.backend.product.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.domain.exception.ProductErrorCode;
import com.justcommit.backend.product.infrastructure.repository.SpeciesRepository;
import com.justcommit.backend.product.infrastructure.repository.SpeciesSpecifications;
import com.justcommit.backend.product.presentation.dto.SpeciesDetailResponse;
import com.justcommit.backend.product.presentation.dto.SpeciesSearchRequest;
import com.justcommit.backend.product.presentation.dto.SpeciesSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpeciesService {

    private final SpeciesRepository speciesRepository;

    public Page<SpeciesSearchResponse> searchSpecies(SpeciesSearchRequest request) {
        return speciesRepository.findAll(
                        SpeciesSpecifications.searchCondition(request.keyword(), request.category()),
                        request.toPageable())
                .map(SpeciesSearchResponse::from);
    }

    public SpeciesDetailResponse getSpeciesDetail(Long speciesId) {
        return speciesRepository.findWithPicturesById(speciesId)
                .map(SpeciesDetailResponse::from)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.SPECIES_NOT_FOUND));
    }
}
