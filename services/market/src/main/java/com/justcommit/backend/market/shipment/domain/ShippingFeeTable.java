package com.justcommit.backend.market.shipment.domain;

import com.justcommit.backend.market.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 택배사(404-express)에게 받은 배송비 요금표 한 버전 (V11). 받은 내용을 고치지 않고 버전마다 새 행으로 쌓는다
@Entity
@Table(name = "shipping_fee_table")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class ShippingFeeTable extends BaseTimeEntity {

    // 택배사가 요금표를 바꿀 때마다 1씩 올리는 번호. 같거나 낮은 버전은 저장하지 않는다 (UK)
    @Column(nullable = false, unique = true)
    private Integer version;

    // 택배사가 이 버전을 만든 시각 (서울 기준)
    @Column(nullable = false)
    private LocalDateTime courierUpdatedAt;

    @Column(nullable = false)
    private BigDecimal baseFee;

    @Column(nullable = false)
    private BigDecimal jejuSurcharge;

    @Column(nullable = false)
    private BigDecimal remoteSurcharge;

    @Convert(converter = ZipcodeRangesConverter.class)
    @ColumnTransformer(write = "?::jsonb")
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<ZipcodeRange> jejuZipcodes;

    @Convert(converter = ZipcodeRangesConverter.class)
    @ColumnTransformer(write = "?::jsonb")
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<ZipcodeRange> remoteZipcodes;

    public ShippingFeeTable(Integer version, LocalDateTime courierUpdatedAt, BigDecimal baseFee,
                            BigDecimal jejuSurcharge, BigDecimal remoteSurcharge,
                            List<ZipcodeRange> jejuZipcodes, List<ZipcodeRange> remoteZipcodes) {
        this.version = version;
        this.courierUpdatedAt = courierUpdatedAt;
        this.baseFee = baseFee;
        this.jejuSurcharge = jejuSurcharge;
        this.remoteSurcharge = remoteSurcharge;
        this.jejuZipcodes = List.copyOf(jejuZipcodes);
        this.remoteZipcodes = List.copyOf(remoteZipcodes);
    }

    // 배송비 = 기본 + 지역 할증 하나. 도서산간 구간을 먼저 보고, 아니면 제주 구간, 아니면 할증 0.
    // 제주 안 섬(추자도 · 우도)은 도서산간 구간에 들어 있어 도서산간 할증만 붙는다 (404-express docs/design.md 「배송비 요금표」)
    public BigDecimal feeFor(String zipcode) {
        if (remoteZipcodes.stream().anyMatch(range -> range.contains(zipcode))) {
            return baseFee.add(remoteSurcharge);
        }
        if (jejuZipcodes.stream().anyMatch(range -> range.contains(zipcode))) {
            return baseFee.add(jejuSurcharge);
        }
        return baseFee;
    }
}
