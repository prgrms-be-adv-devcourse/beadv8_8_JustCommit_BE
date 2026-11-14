package com.justcommit.backend.product.review.domain.enums;

// 엔진이 본 판매 형태. 심지 않은 형태(씨앗 · 삽수 · 구근)는 판매 불가
public enum SaleForm {
    PLANT,      // 심어진 식물 (화분 · 묘목 등)
    SEED,       // 씨앗
    CUTTING,    // 삽수 (꺾꽂이용 줄기 · 잎)
    BULB;       // 구근 · 알뿌리

    public boolean isProhibited() {
        return this != PLANT;
    }
}
