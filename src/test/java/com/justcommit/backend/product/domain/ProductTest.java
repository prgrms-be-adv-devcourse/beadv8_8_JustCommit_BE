package com.justcommit.backend.product.domain;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.domain.exception.ProductErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

class ProductTest {

    // 테스트용 품종을 만드는 메서드
    private Species species (boolean banned){
        Species species = BeanUtils.instantiateClass(Species.class);
        //Species 는 생성자가 protected 여서  BeanUtils.instantiateClass는 생성자 접근 제한을 무시하고 강제로 객체 생성

        // Null 이 될 수 없는 값들만 집어넣음
        ReflectionTestUtils.setField(species, "name", "몬스테라");
        ReflectionTestUtils.setField(species, "banned", banned);
        return species;
    }

    // 상품 생성
    private Product create(Species species){
        return Product.create(species, 1L, "몬스테라 분양합니다", "설명", new BigDecimal("30000"),
                null, null, null, List.of("https://example.com/a.jpg"));
    }

    @Test
    @DisplayName("금지 품종으로는 상품을 생성할 수 없음 (BANNED_SPECIES)")
    void createWithBannedSpecies() {
        assertThatThrownBy(() -> create(species(true)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ProductErrorCode.BANNED_SPECIES);
    }

    @Test
    @DisplayName("상품을 생성하면 판매 중·숨김 아님 상태이고 첫 사진이 썸네일")
    void create() {
        Product product = create(species(false));

        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(product.isHidden()).isFalse();
    }
}
