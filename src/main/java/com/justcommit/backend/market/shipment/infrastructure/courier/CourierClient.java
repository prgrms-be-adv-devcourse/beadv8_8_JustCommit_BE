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
}
