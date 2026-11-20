package com.justcommit.backend.market.shipment.infrastructure;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.shipment.application.ShippingFeeTableService;
import com.justcommit.backend.market.shipment.domain.ShippingFeeTable;
import com.justcommit.backend.market.shipment.domain.ZipcodeRange;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShippingFeeTableRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// V11 요금표가 실제 DB(jsonb · 버전 UK)에 저장되고, 같거나 낮은 버전은 무시되는지 확인한다 (#589)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ShippingFeeTableService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShippingFeeTablePersistenceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Autowired ShippingFeeTableService shippingFeeTableService;
    @Autowired ShippingFeeTableRepository shippingFeeTableRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table shipping_fee_table");
    }

    @Test
    @DisplayName("받은 요금표를 우편번호 구간까지 그대로 저장하고 다시 읽는다")
    void saveAndRead() {
        boolean saved = shippingFeeTableService.save(table(1, 3_000));

        assertThat(saved).isTrue();
        ShippingFeeTable read = shippingFeeTableRepository.findTopByOrderByVersionDesc().orElseThrow();
        assertThat(read.getVersion()).isEqualTo(1);
        assertThat(read.getBaseFee()).isEqualByComparingTo("3000");
        assertThat(read.getJejuZipcodes()).containsExactly(new ZipcodeRange("63000", "63644", "제주특별자치도"));
        assertThat(read.getRemoteZipcodes()).containsExactly(
                new ZipcodeRange("40200", "40240", "경북 울릉도 전지역"),
                new ZipcodeRange("63000", "63001", "제주 추자면"));
        assertThat(jdbcTemplate.queryForObject(
                "select jsonb_array_length(remote_zipcodes) from shipping_fee_table", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 버전을 다시 받으면(재전송) 저장하지 않는다")
    void ignoreSameVersion() {
        shippingFeeTableService.save(table(1, 3_000));

        boolean saved = shippingFeeTableService.save(table(1, 9_999));

        assertThat(saved).isFalse();
        assertThat(shippingFeeTableRepository.count()).isEqualTo(1);
        assertThat(shippingFeeTableRepository.findTopByOrderByVersionDesc().orElseThrow().getBaseFee())
                .isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("낮은 버전이 늦게 와도(순서 꼬임) 저장하지 않고, 높은 버전은 새 행으로 쌓는다")
    void ignoreLowerVersion() {
        shippingFeeTableService.save(table(1, 3_000));
        shippingFeeTableService.save(table(3, 4_000));

        boolean saved = shippingFeeTableService.save(table(2, 3_500));

        assertThat(saved).isFalse();
        assertThat(shippingFeeTableRepository.count()).isEqualTo(2);
        assertThat(shippingFeeTableService.currentVersion()).contains(3);
    }

    private static ShippingFeeTable table(int version, long baseFee) {
        return new ShippingFeeTable(version, LocalDateTime.of(2026, 11, 18, 10, 0),
                BigDecimal.valueOf(baseFee), BigDecimal.valueOf(3_000), BigDecimal.valueOf(5_000),
                List.of(new ZipcodeRange("63000", "63644", "제주특별자치도")),
                List.of(new ZipcodeRange("40200", "40240", "경북 울릉도 전지역"),
                        new ZipcodeRange("63000", "63001", "제주 추자면")));
    }
}
