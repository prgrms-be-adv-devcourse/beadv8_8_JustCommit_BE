package com.justcommit.backend.market.shipment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 배송비 = 기본 + 지역 할증 하나 (도서산간 → 제주 순). 404-express 초기 요금표(3,000 / +3,000 / +5,000)와 같은 값으로 본다 (#589)
class ShippingFeeTableTest {

    private final ShippingFeeTable table = new ShippingFeeTable(1, LocalDateTime.of(2026, 11, 18, 10, 0),
            BigDecimal.valueOf(3_000), BigDecimal.valueOf(3_000), BigDecimal.valueOf(5_000),
            List.of(new ZipcodeRange("63000", "63644", "제주특별자치도")),
            List.of(new ZipcodeRange("40200", "40240", "경북 울릉도 전지역"),
                    new ZipcodeRange("63000", "63001", "제주 추자면")));

    @ParameterizedTest(name = "{0} ({2}) → {1}원")
    @DisplayName("배송지 우편번호로 배송비를 계산한다")
    @CsvSource({
            "06236, 3000, 그 외 지역은 기본만",
            "63100, 6000, 제주는 제주 할증",
            "63644, 6000, 제주 구간 끝(양끝 포함)",
            "63645, 3000, 제주 구간 바로 다음은 기본",
            "40200, 8000, 도서산간 구간 시작(양끝 포함)",
            "40240, 8000, 도서산간 구간 끝(양끝 포함)",
            "63000, 8000, 제주 안 섬은 도서산간 할증 하나만(겹쳐 더하지 않음)"
    })
    void feeFor(String zipcode, long expected, String description) {
        assertThat(table.feeFor(zipcode)).isEqualByComparingTo(BigDecimal.valueOf(expected));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("배송지 우편번호가 어느 할증 구간인지 알려 준다 (도서산간 → 제주 순)")
    @CsvSource({
            "06236, GENERAL",
            "63100, JEJU",
            "40200, REMOTE",
            "63000, REMOTE"
    })
    void regionOf(String zipcode, ShippingRegion expected) {
        assertThat(table.regionOf(zipcode)).isEqualTo(expected);
    }
}
