package com.justcommit.backend.market.shipment.presentation;

import com.justcommit.backend.market.shipment.application.ShipmentWebhookService;
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
        shipmentWebhookService.receive(request);

        return ResponseEntity.ok().build();
    }
}