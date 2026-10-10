package com.justcommit.backend.member.infrastructure.repository;

import com.justcommit.backend.member.domain.Seller;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerRepository extends JpaRepository<Seller, Long> {
}
