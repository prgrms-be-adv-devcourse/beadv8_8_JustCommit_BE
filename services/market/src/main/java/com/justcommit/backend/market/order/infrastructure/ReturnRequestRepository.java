package com.justcommit.backend.market.order.infrastructure;

import com.justcommit.backend.market.order.domain.ReturnRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {

    Optional<ReturnRequest> findByOrderItemId(Long orderItemId);
}
