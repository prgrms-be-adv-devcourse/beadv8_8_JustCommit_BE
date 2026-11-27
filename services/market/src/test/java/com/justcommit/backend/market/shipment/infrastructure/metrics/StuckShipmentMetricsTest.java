package com.justcommit.backend.market.shipment.infrastructure.metrics;

import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// 오래 남은 배송 지표가 실제 DB 기준으로 10분 넘은 READY · CANCEL_PENDING만 세는지 확인한다 (#490).
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StuckShipmentMetricsTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Autowired ShipmentRepository shipmentRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    MeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        new StuckShipmentMetrics(shipmentRepository, registry);
    }

    @AfterEach
    void cleanUp() {
        shipmentRepository.deleteAll();
    }

    @Test
    @DisplayName("만든 지 10분 넘은 READY와 취소를 누른 지 10분 넘은 CANCEL_PENDING만 센다")
    void countsOnlyStuckShipments() {
        Shipment oldReady = saved(1L);
        saved(2L); // 방금 만든 READY → 세지 않음
        Shipment oldCancel = saved(3L);
        Shipment newCancel = saved(4L);
        Shipment booked = saved(5L);
        oldCancel.requestCancel(LocalDateTime.now().minusMinutes(11));
        newCancel.requestCancel(LocalDateTime.now().minusMinutes(1));
        booked.book("404000000005", LocalDateTime.now());
        shipmentRepository.saveAll(java.util.List.of(oldCancel, newCancel, booked));
        // 만든 시각은 감사(auditing)가 채우므로 SQL로 과거로 옮긴다
        jdbcTemplate.update("update shipment set created_at = now() - interval '11 minutes' where id in (?, ?, ?)",
                oldReady.getId(), oldCancel.getId(), booked.getId());

        assertThat(gauge("READY")).isEqualTo(1.0);          // oldReady만 (booked는 READY가 아님)
        assertThat(gauge("CANCEL_PENDING")).isEqualTo(1.0); // oldCancel만
    }

    @Test
    @DisplayName("#671 확인 필요 배송은 10분을 기다리지 않고 바로 센다")
    void countsReviewRequiredImmediately() {
        Shipment review = saved(1L);
        saved(2L);
        review.markReviewRequired("PERMANENT 400");
        shipmentRepository.save(review);

        assertThat(gauge("REVIEW_REQUIRED")).isEqualTo(1.0);
        assertThat(gauge("READY")).isZero(); // 방금 만든 READY는 아직 세지 않음
    }

    @Test
    @DisplayName("오래 남은 배송이 없으면 0")
    void zeroWhenNothingStuck() {
        saved(1L);

        assertThat(gauge("READY")).isZero();
        assertThat(gauge("CANCEL_PENDING")).isZero();
        assertThat(gauge("REVIEW_REQUIRED")).isZero();
    }

    private double gauge(String status) {
        return registry.get(StuckShipmentMetrics.NAME).tag("status", status).gauge().value();
    }

    private Shipment saved(long sellerOrderId) {
        Shipment shipment = shipmentRepository.save(new Shipment(sellerOrderId, "몬스테라"));
        shipment.assignCourierOrderNo(LocalDate.of(2026, 11, 17));
        return shipmentRepository.save(shipment);
    }
}
