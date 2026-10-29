package com.justcommit.backend.market.shipment.presentation;

import com.justcommit.backend.market.shipment.application.ShipmentWebhookService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CourierWebhookControllerTest {

    private static final String WEBHOOK_PATH = "/api/v1/webhooks/courier";
    private static final String BODY = """
            {"eventId":"evt_1","courierOrderNo":"shipment-20261012-000045","trackingNo":"404000000001",
             "status":"PICKED_UP","occurredAt":"2026-10-12T10:03:00+09:00"}
            """;

    @Mock ShipmentWebhookService shipmentWebhookService;

    private MockMvc mockMvc(String configuredKey) {
        CourierWebhookController controller = new CourierWebhookController(shipmentWebhookService);
        ReflectionTestUtils.setField(controller, "webhookApiKey", configuredKey); // @Value 대신 직접 넣기
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("키가 맞으면 웹훅을 처리하고 200을 돌려준다")
    void validKey() throws Exception {
        mockMvc("webhook-key").perform(post(WEBHOOK_PATH)
                        .header("X-Api-Key", "webhook-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());

        verify(shipmentWebhookService).receive(any());
    }

    @Test
    @DisplayName("키가 틀리면 401을 돌려주고 서비스를 부르지 않는다")
    void wrongKey() throws Exception {
        mockMvc("webhook-key").perform(post(WEBHOOK_PATH)
                        .header("X-Api-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verify(shipmentWebhookService, never()).receive(any());
    }

    @Test
    @DisplayName("키 헤더가 없으면 401을 돌려준다")
    void missingKey() throws Exception {
        mockMvc("webhook-key").perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verify(shipmentWebhookService, never()).receive(any());
    }

    @Test
    @DisplayName("서버에 키가 설정되지 않았으면 빈 키를 보내도 401을 돌려준다")
    void keyNotConfigured() throws Exception {
        mockMvc("").perform(post(WEBHOOK_PATH)
                        .header("X-Api-Key", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verify(shipmentWebhookService, never()).receive(any());
    }
}
