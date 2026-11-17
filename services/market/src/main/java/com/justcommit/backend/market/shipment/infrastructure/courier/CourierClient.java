package com.justcommit.backend.market.shipment.infrastructure.courier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

@Component
public class CourierClient {
    private final RestClient restClient;
    private static final String BOOKING_PATH = "/api/v1/shipments";

    public CourierClient(
            @Value("${custom.courier.base-url}") String baseUrl,
            @Value("${custom.courier.api-key}") String apiKey
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        // 연결까지 최대 3초
        factory.setConnectTimeout(Duration.ofSeconds(3));
        // 응답까지 최대 5초
        factory.setReadTimeout(Duration.ofSeconds(5));

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeader("X-Api-Key", apiKey)
                .build();
    }

    public CourierBookingResponse book(String courierOrderNo, CourierBookingRequest request) {
        try {
            CourierApiResponse<CourierBookingResponse> response = restClient.post()
                    .uri(BOOKING_PATH)
                    .header("Idempotency-Key", courierOrderNo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            return response.data();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            boolean retryable = e.getStatusCode().is5xxServerError() || status == 408 || status == 429;

            throw new CourierBookingException(
                    "택배 예약 실패: courierOrderNo = " + courierOrderNo + ", " +
                    "status = " + status, e, retryable);
        } catch (ResourceAccessException e) {
            throw new CourierBookingException(
                    "택배 예약 실패: courierOrderNo = " + courierOrderNo + ", " +
                    "택배사 응답 없음 (시간 초과 또는 연결 실패)", e, true);
        }
    }

    // 주문번호(courierOrderNo)로 택배사 예약을 찾는다. 택배사에 예약이 없으면 빈 값.
    // 송장번호를 모르는(READY에서 취소한) 배송을 취소할 때 쓴다.
    public Optional<CourierShipmentView> findByCourierOrderNo(String courierOrderNo) {
        try {
            CourierApiResponse<CourierShipmentView> response = restClient.get()
                    .uri(BOOKING_PATH + "/byOrderNo/{courierOrderNo}", courierOrderNo)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return Optional.ofNullable(response).map(CourierApiResponse::data);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw new CourierCancelException(
                    "택배 예약 조회 실패: courierOrderNo = " + courierOrderNo + ", status = " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new CourierCancelException(
                    "택배 예약 조회 실패: courierOrderNo = " + courierOrderNo + ", 택배사 응답 없음 (시간 초과 또는 연결 실패)", e);
        }
    }

    // 주문번호로 택배사 예약을 취소한다 (ADR 0025). 택배사는 구매자가 누른 시각의 기사 거리로 판정한다.
    // 200이면 취소됨(반송 포함), 409면 취소 불가(누른 시각에 기사 2km 안). 같은 주문번호는 첫 판정을 그대로 돌려준다.
    // 예약 전 취소도 택배사가 기록해 두고, 늦게 도착한 같은 주문번호 예약은 받지 않는다.
    public Optional<CourierOrderCancelResponse> cancelByCourierOrderNo(String courierOrderNo, OffsetDateTime cancelRequestedAt) {
        try {
            CourierApiResponse<CourierOrderCancelResponse> response = restClient.post()
                    .uri(BOOKING_PATH + "/byOrderNo/{courierOrderNo}/cancel", courierOrderNo)
                    .contentType(MediaType.APPLICATION_JSON)
                    // 시간대가 붙은 ISO-8601 문자열로 보낸다 (예: 2026-11-17T10:00:00+09:00)
                    .body(Map.of("cancelRequestedAt", cancelRequestedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return Optional.ofNullable(response).map(CourierApiResponse::data);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 409) {
                return Optional.empty(); // 취소 불가
            }
            throw new CourierCancelException(
                    "택배 취소 실패: courierOrderNo = " + courierOrderNo + ", status = " + status, e);
        } catch (ResourceAccessException e) {
            throw new CourierCancelException(
                    "택배 취소 실패: courierOrderNo = " + courierOrderNo + ", 택배사 응답 없음 (시간 초과 또는 연결 실패)", e);
        }
    }

    // 옛 송장번호 취소 (택배사에서 삭제됨). 옛 대사 코드가 지워지면 함께 지운다.
    public CourierCancelResult cancel(String trackingNo) {
        try {
            restClient.post()
                    .uri(BOOKING_PATH + "/{trackingNo}/cancel", trackingNo)
                    .retrieve()
                    .toBodilessEntity();
            return CourierCancelResult.CANCELLED;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404) {
                return CourierCancelResult.NOT_FOUND;
            }
            if (status == 409) {
                return CourierCancelResult.NOT_CANCELLABLE;
            }
            throw new CourierCancelException(
                    "택배 취소 실패: trackingNo = " + trackingNo + ", status = " + status, e);
        } catch (ResourceAccessException e) {
            throw new CourierCancelException(
                    "택배 취소 실패: trackingNo = " + trackingNo + ", 택배사 응답 없음 (시간 초과 또는 연결 실패)", e);
        }
    }
}
