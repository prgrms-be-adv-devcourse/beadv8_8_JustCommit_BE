package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.shipment.domain.ShippingFeeTable;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierShippingFeeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShippingFeeReconcileService {

    private final CourierShippingFeeClient courierShippingFeeClient;
    private final ShippingFeeTableService shippingFeeTableService;

    // 요금표 버전 대사: 택배사 요금표 버전이 우리 것과 다르면 받아 저장한다 (웹훅이 끝내 실패했을 때의 안전망).
    // 택배사 호출이 실패하면 로그만 남기고 다음 날 다시 맞춘다. 택배사 버전이 더 낮으면 저장하지 않는다(save가 무시)
    public void reconcile() {
        Optional<ShippingFeeTable> remote;
        try {
            remote = courierShippingFeeClient.fetchCurrent();
        } catch (RestClientException e) {
            log.error("요금표 버전 대사 실패 (택배사 호출): {}", e.getMessage());
            return;
        }
        if (remote.isEmpty()) {
            log.warn("요금표 버전 대사: 택배사에 요금표가 없습니다");
            return;
        }
        Integer remoteVersion = remote.get().getVersion();
        Optional<Integer> localVersion = shippingFeeTableService.currentVersion();
        if (localVersion.isPresent() && localVersion.get().equals(remoteVersion)) {
            log.info("요금표 버전 대사: 버전이 같습니다 (version={})", remoteVersion);
            return;
        }
        log.warn("요금표 버전 대사: 버전이 다릅니다 (우리={}, 택배사={})", localVersion.orElse(null), remoteVersion);
        shippingFeeTableService.save(remote.get());
    }
}
