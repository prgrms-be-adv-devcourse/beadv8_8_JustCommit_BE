package com.justcommit.backend.market.shipment.infrastructure;

import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentCancelResult;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentCancelResultRepository;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V6(취소 누른 시각 · 실패 원인 · 택배 취소 결과 테이블)가 실제 DB에 적용되고 엔티티와 맞는지 확인한다 (#490).
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShipmentCancelPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ShipmentRepository shipmentRepository;
    @Autowired ShipmentCancelResultRepository cancelResultRepository;

    @AfterEach
    void cleanUp() {
        cancelResultRepository.deleteAll();
        shipmentRepository.deleteAll();
    }

    @Test
    @DisplayName("취소 대기 상태 · 누른 시각 · 실패 원인이 저장되고 다시 읽힌다")
    void savesCancelPendingColumns() {
        Shipment shipment = savedShipment(7L);
        LocalDateTime requestedAt = LocalDateTime.of(2026, 11, 17, 10, 0);
        shipment.requestCancel(requestedAt);
        shipment.recordAttemptFailure(requestedAt.plusSeconds(10), "CourierCancelException: 타임아웃");
        shipmentRepository.save(shipment);

        Shipment read = shipmentRepository.findById(shipment.getId()).orElseThrow();
        assertThat(read.getStatus()).isEqualTo(ShipmentStatus.CANCEL_PENDING);
        assertThat(read.getCancelRequestedAt()).isEqualTo(requestedAt);
        assertThat(read.getLastError()).isEqualTo("CourierCancelException: 타임아웃");
        assertThat(read.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("택배 취소 결과는 배송마다 한 건만 남는다")
    void oneCancelResultPerShipment() {
        Shipment shipment = savedShipment(7L);
        LocalDateTime decidedAt = LocalDateTime.of(2026, 11, 17, 10, 1);
        cancelResultRepository.save(ShipmentCancelResult.cancelled(shipment, true, decidedAt));

        ShipmentCancelResult read = cancelResultRepository.findByShipmentId(shipment.getId()).orElseThrow();
        assertThat(read.getResult()).isEqualTo(ShipmentCancelResult.Result.CANCELLED);
        assertThat(read.isCourierReturn()).isTrue();
        assertThat(read.getSellerOrderId()).isEqualTo(7L);
        assertThat(read.getDecidedAt()).isEqualTo(decidedAt);
        assertThat(read.getProcessedAt()).isNull();

        assertThatThrownBy(() -> cancelResultRepository.save(ShipmentCancelResult.locked(shipment, decidedAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Shipment savedShipment(long sellerOrderId) {
        Shipment shipment = shipmentRepository.save(new Shipment(sellerOrderId, "몬스테라"));
        shipment.assignCourierOrderNo(LocalDate.of(2026, 11, 17));
        shipment.book("404000000001", LocalDateTime.of(2026, 11, 17, 9, 0));
        return shipmentRepository.save(shipment);
    }
}
