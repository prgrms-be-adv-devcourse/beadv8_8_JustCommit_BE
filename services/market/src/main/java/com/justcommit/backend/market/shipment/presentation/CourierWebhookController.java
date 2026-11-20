package com.justcommit.backend.market.shipment.presentation;

import com.justcommit.backend.market.shipment.application.ShipmentWebhookService;
import com.justcommit.backend.market.shipment.application.ShippingFeeTableService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;

@RestController
@RequiredArgsConstructor
public class CourierWebhookController {

    private final ShipmentWebhookService shipmentWebhookService;
    private final ShippingFeeTableService shippingFeeTableService;

    @Value("${custom.courier.webhook-api-key}")
    private String webhookApiKey;

    @PostMapping("/api/v1/webhooks/courier")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @Valid @RequestBody CourierWebhookRequest request) {
        if (webhookApiKey.isBlank() ||
                !MessageDigest.isEqual(webhookApiKey.getBytes(), apiKey == null ? new byte[0] : apiKey.getBytes())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 요금표 변경은 배송 건이 없어 요금표 저장으로, 나머지는 배송 상태 반영으로. 둘 다 무시한 경우도 200 (택배사가 다시 보내지 않게)
        if (request.isShippingFeesChanged()) {
            shippingFeeTableService.save(request.toShippingFeeTable());
        } else {
            shipmentWebhookService.receive(request);
        }

        return ResponseEntity.ok().build();
    }
}