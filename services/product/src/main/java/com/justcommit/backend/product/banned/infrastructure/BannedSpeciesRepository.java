package com.justcommit.backend.product.banned.infrastructure;

import com.justcommit.backend.product.banned.domain.BannedSpecies;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BannedSpeciesRepository extends JpaRepository<BannedSpecies, Long> {

    // 별칭까지 한 번에 (118줄이라 전부 읽음)
    @Query("select distinct s from BannedSpecies s left join fetch s.aliases")
    List<BannedSpecies> findAllWithAliases();
}
