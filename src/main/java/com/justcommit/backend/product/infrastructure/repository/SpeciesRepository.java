package com.justcommit.backend.product.infrastructure.repository;

import com.justcommit.backend.product.domain.Species;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface SpeciesRepository extends JpaRepository<Species,Long> , JpaSpecificationExecutor<Species> {

    @EntityGraph(attributePaths = "pictures")
    Optional<Species> findWithPicturesById(Long id);
}
