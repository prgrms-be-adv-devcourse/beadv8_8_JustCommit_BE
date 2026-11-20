package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.market.ShipmentTracking;
import com.justcommit.backend.market.order.application.OrderShipmentTrackingService;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ShipmentTrackingControllerTest {

    private static final LocalDateTime BOOKED_AT = LocalDateTime.of(2026, 11, 20, 10, 0);

    @Mock OrderShipmentTrackingService trackingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ShipmentTrackingController(trackingService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("판매자 배송 조회: 단계 · 시각 · 남은 거리 · 지점을 돌려준다")
    void sellerShipment() throws Exception {
        login(20L);
        when(trackingService.sellerShipment(20L, 7L)).thenReturn(new ShipmentTracking("BOOKED", "404000000001",
                BOOKED_AT, null, null, null, null, "NEAR_5KM", 4321, "강남우체국", BOOKED_AT.plusMinutes(3)));

        mockMvc.perform(get("/api/v1/orders/seller/7/shipment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerOrderId").value(7))
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.trackingNo").value("404000000001"))
                .andExpect(jsonPath("$.bookedAt").value("2026-11-20T10:00:00"))
                .andExpect(jsonPath("$.pickedUpAt").isEmpty())
                .andExpect(jsonPath("$.courierStatus").value("NEAR_5KM"))
                .andExpect(jsonPath("$.remainingDistanceMeters").value(4321))
                .andExpect(jsonPath("$.locationName").value("강남우체국"))
                .andExpect(jsonPath("$.checkedAt").value("2026-11-20T10:03:00"));
    }

    @Test
    @DisplayName("구매자 배송 조회: 택배사 조회가 실패해도 200, 택배사 칸만 null")
    void buyerShipmentWithoutCourierView() throws Exception {
        login(10L);
        when(trackingService.buyerShipment(10L, 9L, 7L)).thenReturn(new ShipmentTracking("BOOKED", "404000000001",
                BOOKED_AT, null, null, null, null, null, null, null, null));

        mockMvc.perform(get("/api/v1/orders/9/sellerOrders/7/shipment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerOrderId").value(7))
                .andExpect(jsonPath("$.bookedAt").value("2026-11-20T10:00:00"))
                .andExpect(jsonPath("$.remainingDistanceMeters").isEmpty())
                .andExpect(jsonPath("$.locationName").isEmpty())
                .andExpect(jsonPath("$.checkedAt").isEmpty());
    }

    @Test
    @DisplayName("배송이 아직 없으면 404 SHIPMENT_TRACKING_NOT_FOUND")
    void noShipmentYet() throws Exception {
        login(20L);
        when(trackingService.sellerShipment(20L, 7L))
                .thenThrow(new BusinessException(OrderErrorCode.SHIPMENT_TRACKING_NOT_FOUND));

        mockMvc.perform(get("/api/v1/orders/seller/7/shipment"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHIPMENT_TRACKING_NOT_FOUND"));
    }

    @Test
    @DisplayName("로그인하지 않으면 401, 서비스를 부르지 않는다")
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/api/v1/orders/9/sellerOrders/7/shipment"))
                .andExpect(status().isUnauthorized());
        verify(trackingService, never()).buyerShipment(anyLong(), anyLong(), anyLong());
    }

    private void login(Long memberId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthMember(memberId, "MEMBER"), null, List.of()));
    }
}
