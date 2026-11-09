package com.justcommit.backend.member.seller.infrastructure;

import com.justcommit.backend.member.seller.domain.Seller;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerRepository extends JpaRepository<Seller, Long> {
}
