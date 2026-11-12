package com.justcommit.backend.product.product.event;


import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

public record ProductRegistered(Long productId) implements IntegrationEvent {
    @Override
    public  String topic(){
        return KafkaTopics.PRODUCT_EVENTS;
    }
    @Override
    public String eventType() {
        return "ProductRegistered";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(productId);
    }
}
