package com.justcommit.backend.market.shipment.infrastructure.courier;

import com.justcommit.backend.common.messaging.crypto.EventFieldCipher;
import com.justcommit.backend.common.messaging.crypto.EventFieldCipherException;
import com.justcommit.backend.market.config.BookingRequestCipherConfig.BookingRequestCipherProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static com.justcommit.backend.market.shipment.infrastructure.courier.TestBookingRequestCodecConfig.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// #688: 택배 예약 본문은 market 전용 키로 암호화하고, 전환 기간에는 판매자 배송지 키(k1)로 저장된 값도 읽는다
class CourierBookingRequestCodecTest {

    private final EventFieldCipher addressEventCipher = new EventFieldCipher("k1", Map.of("k1", newKey()));
    private final CourierBookingRequestCodec codec = new CourierBookingRequestCodec(
            new BookingRequestCipherProperties("b1", Map.of("b1", newKey())), addressEventCipher);

    private final CourierBookingRequest request = new CourierBookingRequest(
            new CourierBookingRequest.Party("김판매", "010-1111-2222", "서울시 강남구"),
            new CourierBookingRequest.Party("이구매", "010-3333-4444", "부산시 해운대구"),
            "몬스테라");

    @Test
    @DisplayName("market 전용 키(b1)로 암호화해 판매자 배송지 키(member도 가진 키)로는 풀리지 않고, 다시 읽으면 같은 본문")
    void encodesWithMarketOnlyKey() {
        String encoded = codec.encode(request);

        assertThat(encoded).startsWith("v1.b1:").doesNotContain("서울시");
        assertThat(codec.decode(encoded)).isEqualTo(request);
        assertThatThrownBy(() -> addressEventCipher.decrypt(encoded)).isInstanceOf(EventFieldCipherException.class);
    }

    @Test
    @DisplayName("전환 기간: #688 배포 전에 판매자 배송지 키(k1)로 저장된 본문도 읽는다")
    void decodesLegacyAddressKeyValue() {
        String legacy = addressEventCipher.encrypt(JsonMapper.builder().build().writeValueAsString(request));

        assertThat(legacy).startsWith("v1.k1:");
        assertThat(codec.decode(legacy)).isEqualTo(request);
    }

    @Test
    @DisplayName("모르는 키 버전 · 깨진 값은 원문 없이 실패한다")
    void unreadableValueFails() {
        assertThatThrownBy(() -> codec.decode("v1.zz:AAAA"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("AAAA");
    }

    @Test
    @DisplayName("키 설정이 비었으면 만들 때 실패한다 (앱이 시작하지 않음)")
    void missingKeyFailsFast() {
        assertThatThrownBy(() -> new CourierBookingRequestCodec(
                new BookingRequestCipherProperties("b1", Map.of("b1", "")), addressEventCipher))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
