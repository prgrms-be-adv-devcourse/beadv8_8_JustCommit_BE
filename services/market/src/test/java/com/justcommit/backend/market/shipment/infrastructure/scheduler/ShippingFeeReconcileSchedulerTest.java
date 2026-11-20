package com.justcommit.backend.market.shipment.infrastructure.scheduler;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.config.ShedLockConfig;
import com.justcommit.backend.market.shipment.application.ShippingFeeReconcileService;
import com.justcommit.backend.market.shipment.application.ShippingFeeTableService;
import com.justcommit.backend.market.shipment.domain.ShippingFeeTable;
import com.justcommit.backend.market.shipment.domain.ZipcodeRange;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierShippingFeeClient;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 요금표 버전 대사를 실제 DB(V11 요금표 · V12 shedlock)로 확인한다 (#589). 택배사만 가짜
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ShedLockConfig.class, ShippingFeeTableService.class,
        ShippingFeeReconcileService.class, ShippingFeeReconcileScheduler.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShippingFeeReconcileSchedulerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @MockitoBean CourierShippingFeeClient courierShippingFeeClient;
    @Autowired ShippingFeeReconcileScheduler scheduler;
    @Autowired ShippingFeeReconcileService reconcileService;
    @Autowired ShippingFeeTableService shippingFeeTableService;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table shipping_fee_table, shedlock");
    }

    @Test
    @DisplayName("택배사 버전이 더 높으면 새 요금표를 받아 저장한다 (웹훅을 놓친 경우)")
    void savesNewerVersion() {
        shippingFeeTableService.save(table(1, 3_000));
        when(courierShippingFeeClient.fetchCurrent()).thenReturn(Optional.of(table(2, 4_000)));

        reconcileService.reconcile();

        assertThat(shippingFeeTableService.currentVersion()).contains(2);
        assertThat(shippingFeeTableService.feeFor("06236")).isEqualByComparingTo("4000");
    }

    @Test
    @DisplayName("요금표를 한 번도 못 받았으면 택배사 요금표를 저장한다")
    void savesWhenEmpty() {
        when(courierShippingFeeClient.fetchCurrent()).thenReturn(Optional.of(table(1, 3_000)));

        reconcileService.reconcile();

        assertThat(shippingFeeTableService.currentVersion()).contains(1);
    }

    @Test
    @DisplayName("버전이 같으면 저장하지 않는다")
    void sameVersion() {
        shippingFeeTableService.save(table(2, 3_000));
        when(courierShippingFeeClient.fetchCurrent()).thenReturn(Optional.of(table(2, 9_999)));

        reconcileService.reconcile();

        assertThat(jdbcTemplate.queryForObject("select count(*) from shipping_fee_table", Integer.class)).isEqualTo(1);
        assertThat(shippingFeeTableService.feeFor("06236")).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("택배사 호출이 실패하면 예외 없이 끝내고 지금 요금표를 그대로 둔다")
    void courierFailure() {
        shippingFeeTableService.save(table(1, 3_000));
        when(courierShippingFeeClient.fetchCurrent()).thenThrow(new ResourceAccessException("timeout"));

        reconcileService.reconcile();

        assertThat(shippingFeeTableService.currentVersion()).contains(1);
    }

    @Test
    @DisplayName("ShedLock: 잠금을 쥔 동안 다시 불러도(다른 파드) 한 번만 돈다")
    void runsOnceWhileLocked() {
        when(courierShippingFeeClient.fetchCurrent()).thenReturn(Optional.of(table(1, 3_000)));

        scheduler.reconcileShippingFees();
        scheduler.reconcileShippingFees();

        verify(courierShippingFeeClient, times(1)).fetchCurrent();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from shedlock where name = 'shippingFeeReconcile'", Integer.class)).isEqualTo(1);
    }

    private static ShippingFeeTable table(int version, long baseFee) {
        return new ShippingFeeTable(version, LocalDateTime.of(2026, 11, 18, 10, 0),
                BigDecimal.valueOf(baseFee), BigDecimal.valueOf(3_000), BigDecimal.valueOf(5_000),
                List.of(new ZipcodeRange("63000", "63644", "제주특별자치도")),
                List.of(new ZipcodeRange("40200", "40240", "경북 울릉도 전지역")));
    }
}
