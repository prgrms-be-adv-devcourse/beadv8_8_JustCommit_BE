package com.justcommit.backend.product.species.infrastructure;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.domain.SpeciesAlias;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpeciesRepository extends JpaRepository<Species,Long> , JpaSpecificationExecutor<Species> {

    @EntityGraph(attributePaths = "images")
    Optional<Species> findWithImagesById(Long id);

    // 사진 검수 품종 대조용: 별칭 전부와 그 품종
    @Query("SELECT a FROM SpeciesAlias a JOIN FETCH a.species")
    List<SpeciesAlias> findAllAliasesWithSpecies();

    // 검색 색인용: 여러 품종의 별칭을 한 번에 (품종은 id만 쓰므로 함께 읽지 않음)
    @Query("SELECT a FROM SpeciesAlias a WHERE a.species.id IN :speciesIds ORDER BY a.id")
    List<SpeciesAlias> findAliasesBySpeciesIds(@Param("speciesIds") Collection<Long> speciesIds);
}
