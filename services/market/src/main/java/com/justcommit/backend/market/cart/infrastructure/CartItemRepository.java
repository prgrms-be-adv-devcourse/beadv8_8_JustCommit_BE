package com.justcommit.backend.market.cart.infrastructure;

import com.justcommit.backend.market.cart.domain.CartItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    // 장바구니 추천 요청용. 장바구니가 없으면 빈 목록
    @Query("select i.productId from CartItem i where i.cart.memberId = :memberId order by i.id")
    List<Long> findProductIdsByMemberId(@Param("memberId") long memberId);
}
