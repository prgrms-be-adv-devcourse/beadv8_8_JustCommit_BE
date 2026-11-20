package com.justcommit.backend.market.shipment.presentation;

import com.justcommit.backend.market.shipment.application.ShipmentWebhookService;
import com.justcommit.backend.market.shipment.application.ShippingFeeTableService;
import com.justcommit.backend.market.shipment.domain.ShippingFeeTable;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
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

    // 404-express가 기동 때 보내는 첫 요금표 웹훅과 같은 모양 (docs/design.md 「요금표 변경 웹훅」)
    private static final String FEE_BODY = """
            {"eventId":"evt_fee","status":"SHIPPING_FEES_CHANGED","occurredAt":"2026-11-18T10:00:00+09:00",
             "version":1,"updatedAt":"2026-11-18T10:00:00+09:00",
             "baseFee":3000,"jejuSurcharge":3000,"remoteSurcharge":5000,
             "jejuZipcodes":[{"from":"63000","to":"63644","name":"제주특별자치도"}],
             "remoteZipcodes":[{"from":"40200","to":"40240","name":"경북 울릉도 전지역"}]}
            """;

    @Mock ShipmentWebhookService shipmentWebhookService;
    @Mock ShippingFeeTableService shippingFeeTableService;

    private MockMvc mockMvc(String configuredKey) {
        CourierWebhookController controller = new CourierWebhookController(shipmentWebhookService, shippingFeeTableService);
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
    @DisplayName("[S-F04] 키가 틀리면 401을 돌려주고 서비스를 부르지 않는다")
    void wrongKey() throws Exception {
        mockMvc("webhook-key").perform(post(WEBHOOK_PATH)
                        .header("X-Api-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verify(shipmentWebhookService, never()).receive(any());
    }

    @Test
    @DisplayName("[S-F04] 키 헤더가 없으면 401을 돌려준다")
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

    @Test
    @DisplayName("요금표 변경 웹훅은 주문번호 · 송장번호 없이 받아 요금표로 저장하고 200을 돌려준다")
    void shippingFeesChanged() throws Exception {
        mockMvc("webhook-key").perform(post(WEBHOOK_PATH)
                        .header("X-Api-Key", "webhook-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FEE_BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<ShippingFeeTable> captor = ArgumentCaptor.forClass(ShippingFeeTable.class);
        verify(shippingFeeTableService).save(captor.capture());
        ShippingFeeTable saved = captor.getValue();
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getBaseFee()).isEqualByComparingTo("3000");
        assertThat(saved.getRemoteSurcharge()).isEqualByComparingTo("5000");
        assertThat(saved.getJejuZipcodes()).singleElement()
                .satisfies(range -> assertThat(range.from()).isEqualTo("63000"));
        assertThat(saved.getCourierUpdatedAt()).hasToString("2026-11-18T10:00");
        verify(shipmentWebhookService, never()).receive(any());
    }

    @Test
    @DisplayName("요금표 변경 웹훅에 요금표가 빠졌으면 400을 돌려준다")
    void shippingFeesChangedWithoutTable() throws Exception {
        mockMvc("webhook-key").perform(post(WEBHOOK_PATH)
                        .header("X-Api-Key", "webhook-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"evt_fee","status":"SHIPPING_FEES_CHANGED","occurredAt":"2026-11-18T10:00:00+09:00",
                                 "version":2,"baseFee":3000}
                                """))
                .andExpect(status().isBadRequest());

        verify(shippingFeeTableService, never()).save(any());
    }

    @Test
    @DisplayName("요금표의 우편번호가 5자리가 아니면 400을 돌려준다")
    void invalidZipcode() throws Exception {
        mockMvc("webhook-key").perform(post(WEBHOOK_PATH)
                        .header("X-Api-Key", "webhook-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FEE_BODY.replace("\"63644\"", "\"6364\"")))
                .andExpect(status().isBadRequest());

        verify(shippingFeeTableService, never()).save(any());
    }

    @Test
    @DisplayName("배송 상태 웹훅에 주문번호가 없으면 400을 돌려준다")
    void shipmentStatusWithoutCourierOrderNo() throws Exception {
        mockMvc("webhook-key").perform(post(WEBHOOK_PATH)
                        .header("X-Api-Key", "webhook-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"evt_1","trackingNo":"404000000001",
                                 "status":"PICKED_UP","occurredAt":"2026-10-12T10:03:00+09:00"}
                                """))
                .andExpect(status().isBadRequest());

        verify(shipmentWebhookService, never()).receive(any());
    }
}
