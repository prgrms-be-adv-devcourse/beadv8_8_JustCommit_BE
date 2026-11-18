package com.justcommit.backend.product.species.infrastructure;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.domain.SpeciesAlias;
import org.springframework.data.jpa.repository.Query;

public interface SpeciesRepository extends JpaRepository<Species,Long> , JpaSpecificationExecutor<Species> {

    @EntityGraph(attributePaths = "images")
    Optional<Species> findWithImagesById(Long id);

    // 사진 검수 품종 대조용: 별칭 전부와 그 품종
    @Query("SELECT a FROM SpeciesAlias a JOIN FETCH a.species")
    List<SpeciesAlias> findAllAliasesWithSpecies();
}
