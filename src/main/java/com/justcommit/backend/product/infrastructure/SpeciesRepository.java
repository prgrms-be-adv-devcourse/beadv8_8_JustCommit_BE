package com.justcommit.backend.product.infrastructure;

import com.justcommit.backend.product.domain.Species;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpeciesRepository extends JpaRepository<Species,Long> {

    @EntityGraph(attributePaths = "pictures")
    Optional<Species> findWithPicturesByIdAndBannedFalse(Long id);
}
