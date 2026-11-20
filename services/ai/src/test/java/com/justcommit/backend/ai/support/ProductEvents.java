package com.justcommit.backend.ai.support;

import com.justcommit.backend.common.messaging.EventEnvelope;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

// 테스트용 product.events.v1 봉투 (payload v3)
public final class ProductEvents {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ProductEvents() {
    }

    public static EventEnvelope v3(String eventType, long productId, long speciesId, String status, boolean hidden,
            long productVersion, String title, String description) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("productId", productId);
        payload.put("speciesId", speciesId);
        payload.put("sellerId", 7L);
        payload.put("status", status);
        payload.put("hidden", hidden);
        payload.put("productVersion", productVersion);
        payload.put("title", title);
        payload.put("price", 15000);
        payload.put("thumbnailUrl", "https://img.test/" + productId + ".jpg");
        payload.put("description", description);
        payload.put("growNote", null);
        payload.put("heightCm", 30);
        payload.put("potSize", "소형");
        return new EventEnvelope(UUID.randomUUID().toString(), eventType, 3, OffsetDateTime.now(),
                String.valueOf(productId), JSON.valueToTree(payload));
    }
}
