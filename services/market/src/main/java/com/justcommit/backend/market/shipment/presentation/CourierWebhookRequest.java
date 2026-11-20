package com.justcommit.backend.market.shipment.presentation;

import com.justcommit.backend.market.shipment.domain.ShippingFeeTable;
import com.justcommit.backend.market.shipment.domain.ZipcodeRange;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

// 택배사 웹훅 본문. 배송 상태 웹훅과 요금표 변경 웹훅(status = SHIPPING_FEES_CHANGED)이 같은 주소로 온다.
// 요금표 변경에는 배송 건이 없어 courierOrderNo · trackingNo가 없고, 대신 요금표 전체가 온다 (404-express docs/design.md 「요금표 변경 웹훅」)
public record CourierWebhookRequest(
        @NotBlank String eventId,           // 로그 추적용
        String courierOrderNo,              // 배송을 찾는 키 (배송 상태 웹훅만)
        String trackingNo,                  // 배송 상태 웹훅만
        @NotBlank String status,            // 택배사 용어: PICKED_UP / IN_TRANSIT / DELIVERED / SHIPPING_FEES_CHANGED ...
        @NotNull OffsetDateTime occurredAt, // 택배사에서 실제로 일어난 시각 (+09:00)

        // 아래는 요금표 변경 웹훅만
        @Positive Integer version,
        OffsetDateTime updatedAt,
        @PositiveOrZero BigDecimal baseFee,
        @PositiveOrZero BigDecimal jejuSurcharge,
        @PositiveOrZero BigDecimal remoteSurcharge,
        List<@Valid @NotNull ZipcodeRangeRequest> jejuZipcodes,
        List<@Valid @NotNull ZipcodeRangeRequest> remoteZipcodes
) {
    public static final String SHIPPING_FEES_CHANGED = "SHIPPING_FEES_CHANGED";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public CourierWebhookRequest(String eventId, String courierOrderNo, String trackingNo, String status,
                                 OffsetDateTime occurredAt) {
        this(eventId, courierOrderNo, trackingNo, status, occurredAt, null, null, null, null, null, null, null);
    }

    public record ZipcodeRangeRequest(
            @NotNull @Pattern(regexp = "[0-9]{5}") String from,
            @NotNull @Pattern(regexp = "[0-9]{5}") String to,
            String name
    ) {
        @AssertTrue(message = "from은 to보다 클 수 없습니다")
        public boolean isOrdered() {
            return from == null || to == null || from.compareTo(to) <= 0;
        }

        ZipcodeRange toDomain() {
            return new ZipcodeRange(from, to, name);
        }
    }

    public boolean isShippingFeesChanged() {
        return SHIPPING_FEES_CHANGED.equals(status);
    }

    @AssertTrue(message = "배송 상태 웹훅에는 courierOrderNo와 trackingNo가 필요합니다")
    public boolean isShipmentFieldsPresent() {
        return isShippingFeesChanged() || (notBlank(courierOrderNo) && notBlank(trackingNo));
    }

    @AssertTrue(message = "요금표 변경 웹훅에는 요금표 전체가 필요합니다")
    public boolean isShippingFeeFieldsPresent() {
        return !isShippingFeesChanged() || (version != null && updatedAt != null && baseFee != null
                && jejuSurcharge != null && remoteSurcharge != null && jejuZipcodes != null && remoteZipcodes != null);
    }

    public ShippingFeeTable toShippingFeeTable() {
        return new ShippingFeeTable(version, updatedAt.atZoneSameInstant(SEOUL).toLocalDateTime(),
                baseFee, jejuSurcharge, remoteSurcharge,
                jejuZipcodes.stream().map(ZipcodeRangeRequest::toDomain).toList(),
                remoteZipcodes.stream().map(ZipcodeRangeRequest::toDomain).toList());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
