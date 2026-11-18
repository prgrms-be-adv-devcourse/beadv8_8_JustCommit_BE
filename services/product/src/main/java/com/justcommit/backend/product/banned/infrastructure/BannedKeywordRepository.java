package com.justcommit.backend.product.banned.infrastructure;

import com.justcommit.backend.product.banned.domain.BannedKeyword;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BannedKeywordRepository extends JpaRepository<BannedKeyword, Long> {
}
