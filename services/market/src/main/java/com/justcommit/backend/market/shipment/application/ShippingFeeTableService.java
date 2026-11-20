package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.shipment.domain.ShippingFeeTable;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShippingFeeTableRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShippingFeeTableService {

    // 요금표를 한 번도 받지 못했을 때 쓰는 배송비 (세미의 고정 배송비, 11/20 사용자 결정)
    static final BigDecimal DEFAULT_SHIPPING_FEE = BigDecimal.valueOf(3_000);

    private final ShippingFeeTableRepository shippingFeeTableRepository;

    // 배송지 우편번호의 배송비를 지금 요금표(가장 높은 버전)로 계산한다. 주문을 만들 때 판매자 주문마다 저장하므로
    // 새 요금표는 받은 뒤 생성되는 주문부터 쓰인다
    public BigDecimal feeFor(String zipcode) {
        return shippingFeeTableRepository.findTopByOrderByVersionDesc()
                .map(table -> table.feeFor(zipcode))
                .orElseGet(() -> {
                    log.warn("배송비 요금표가 아직 없어 기본 배송비 {}원을 씁니다: zipcode={}", DEFAULT_SHIPPING_FEE, zipcode);
                    return DEFAULT_SHIPPING_FEE;
                });
    }

    // 받은 요금표를 저장한다. 지금 것보다 버전이 높을 때만 저장하고, 같거나 낮으면(재전송 · 순서 꼬임) 무시한다.
    // 두 파드가 같은 버전을 동시에 받으면 버전 UK에 한쪽만 들어가고 나머지는 무시로 끝낸다.
    // 트랜잭션을 걸지 않는다: saveAndFlush가 자기 트랜잭션에서 실패해야 UK 위반을 여기서 잡아 무시할 수 있다
    public boolean save(ShippingFeeTable received) {
        Optional<Integer> currentVersion = shippingFeeTableRepository.findTopByOrderByVersionDesc()
                .map(ShippingFeeTable::getVersion);
        if (currentVersion.isPresent() && received.getVersion() <= currentVersion.get()) {
            log.info("저장하지 않은 요금표 (같거나 낮은 버전): 현재={}, 받은={}", currentVersion.get(), received.getVersion());
            return false;
        }
        try {
            shippingFeeTableRepository.saveAndFlush(received);
        } catch (DataIntegrityViolationException e) {
            log.info("저장하지 않은 요금표 (같은 버전을 먼저 저장함): 받은={}", received.getVersion());
            return false;
        }
        log.info("배송비 요금표 저장: version={}, 기본={}, 제주 할증={}, 도서산간 할증={}",
                received.getVersion(), received.getBaseFee(), received.getJejuSurcharge(), received.getRemoteSurcharge());
        return true;
    }

    // 지금 쓰는 요금표 버전. 아직 한 번도 못 받았으면 빈 값
    public Optional<Integer> currentVersion() {
        return shippingFeeTableRepository.findTopByOrderByVersionDesc().map(ShippingFeeTable::getVersion);
    }
}
