package com.justcommit.backend.market.shipment.infrastructure.courier;

import com.justcommit.backend.market.shipment.domain.ShippingFeeTable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Optional;

// 택배사 요금표 조회. 예약 · 취소(CourierClient)와 따로 둔다: 하루 1번 버전 대사만 쓰고, 실패해도 다음 날 다시 맞춘다
@Component
public class CourierShippingFeeClient {

    private static final String SHIPPING_FEES_PATH = "/api/v1/shippingFees";

    private final RestClient restClient;

    public CourierShippingFeeClient(
            @Value("${custom.courier.base-url}") String baseUrl,
            @Value("${custom.courier.api-key}") String apiKey
    ) {
        // 택배사 호출 공통값: 연결 3초 · 응답 5초 (docs/conventions/resilience.md 「market → 모의 택배사」)
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeader("X-Api-Key", apiKey)
                .build();
    }

    // 택배사의 지금 요금표. 택배사에 요금표가 아직 없으면(404) 빈 값. 그 밖의 실패는 RestClientException 그대로
    public Optional<ShippingFeeTable> fetchCurrent() {
        try {
            CourierApiResponse<CourierShippingFeesResponse> response = restClient.get()
                    .uri(SHIPPING_FEES_PATH)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return Optional.ofNullable(response)
                    .map(CourierApiResponse::data)
                    .map(CourierShippingFeesResponse::toDomain);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
