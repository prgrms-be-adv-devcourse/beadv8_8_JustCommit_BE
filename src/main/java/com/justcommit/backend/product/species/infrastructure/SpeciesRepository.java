package com.justcommit.backend.product.species.infrastructure;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import com.justcommit.backend.product.species.domain.Species;

public interface SpeciesRepository extends JpaRepository<Species,Long> , JpaSpecificationExecutor<Species> {

    @EntityGraph(attributePaths = "pictures")
    Optional<Species> findWithPicturesById(Long id);
}
