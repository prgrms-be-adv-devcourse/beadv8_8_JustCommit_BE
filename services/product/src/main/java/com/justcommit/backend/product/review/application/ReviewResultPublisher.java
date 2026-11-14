package com.justcommit.backend.product.review.application;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.product.product.event.ProductApproved;
import com.justcommit.backend.product.product.event.ProductRejected;
import com.justcommit.backend.product.product.event.ProductStatusChanged;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 검수 결과가 확정돼 상품 상태가 바뀔 때 보내는 이벤트를 한 곳에서 (자동 검수 · #344 관리자 통과 · 반려 공통)
// 상태 반영과 같은 트랜잭션에서 Outbox에 저장: 상태 동기화용 ProductStatusChanged + 판매자 알림용 ProductApproved · ProductRejected
// 관리자 확인으로 넘어가는 경우는 결과가 아니라서 여기를 부르지 않음
@Component
@RequiredArgsConstructor
public class ReviewResultPublisher {

    private final OutboxPublisher outboxPublisher;

    // 통과 → ACTIVE
    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(Long productId, Long sellerId, int reviewVersion, String productTitle) {
        outboxPublisher.publish(new ProductStatusChanged(productId));
        outboxPublisher.publish(new ProductApproved(productId, sellerId, reviewVersion, productTitle));
    }

    // 반려 → REJECTED. adminMemo는 관리자가 OTHER로 반려할 때 입력한 내용 (그 밖에는 null)
    @Transactional(propagation = Propagation.MANDATORY)
    public void rejected(Long productId, Long sellerId, int reviewVersion, String productTitle,
                         RejectReason reason, String adminMemo) {
        String message = reason == RejectReason.OTHER ? adminMemo : reason.getMessage();
        outboxPublisher.publish(new ProductStatusChanged(productId));
        outboxPublisher.publish(new ProductRejected(productId, sellerId, reviewVersion, productTitle, reason.name(), message));
    }
}
