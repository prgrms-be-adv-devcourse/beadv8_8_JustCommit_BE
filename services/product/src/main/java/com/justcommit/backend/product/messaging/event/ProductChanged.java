package com.justcommit.backend.product.messaging.event;

import java.util.Objects;

// 색인 리스너가 product.events.v1 payload에서 읽는 값 (ProductRegistered · ProductUpdated · ProductStatusChanged 공통)
// 다른 필드(상태 · 숨김 · 버전 등)는 읽지 않고 DB에서 다시 읽는다. 모르는 필드는 EventEnvelopeMapper가 무시함
public record ProductChanged(Long productId) {

    public ProductChanged {
        Objects.requireNonNull(productId, "productId");
    }
}
