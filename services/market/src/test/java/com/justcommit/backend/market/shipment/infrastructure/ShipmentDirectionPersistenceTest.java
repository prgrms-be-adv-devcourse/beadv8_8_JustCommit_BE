package com.justcommit.backend.market.shipment.infrastructure;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.shipment.application.ShipmentQueryService;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentDirection;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V10 배송 방향이 실제 DB에 적용되는지 확인한다 (#382): 가는 길은 판매자 주문당 1개, 반품은 여러 개,
// 판매자 주문으로 배송을 찾는 곳은 반품 배송이 있어도 가는 길만 본다
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ShipmentQueryService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShipmentDirectionPersistenceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Autowired ShipmentRepository shipmentRepository;
    @Autowired ShipmentQueryService shipmentQueryService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table shipment");
    }

    @Test
    @DisplayName("새 배송은 가는 길로 저장되고, 기존 행의 기본값도 가는 길이다")
    void newShipmentIsOutbound() {
        Shipment shipment = shipmentRepository.save(new Shipment(7L, "몬스테라"));
        jdbcTemplate.update("insert into shipment (seller_order_id, status, version) values (8, 'READY', 0)");

        assertThat(shipmentRepository.findById(shipment.getId()).orElseThrow().getDirection())
                .isEqualTo(ShipmentDirection.OUTBOUND);
        assertThat(jdbcTemplate.queryForObject("select direction from shipment where seller_order_id = 8", String.class))
                .isEqualTo("OUTBOUND");
    }

    @Test
    @DisplayName("같은 판매자 주문에 반품 배송은 여러 개 저장되고, 가는 길 배송은 두 번째에서 유니크 위반이 난다")
    void outboundIsUniquePerSellerOrderButReturnIsNot() {
        shipmentRepository.save(new Shipment(7L, "몬스테라"));
        insertReturn(7L);
        insertReturn(7L);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from shipment where seller_order_id = 7 and direction = 'RETURN'", Long.class))
                .isEqualTo(2L);
        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(new Shipment(7L, "몬스테라")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("반품 배송이 있어도 판매자 주문 배송 조회와 취소용 잠금 조회는 가는 길 배송만 돌려준다")
    void sellerOrderLookupsReturnOnlyOutbound() {
        Shipment outbound = shipmentRepository.save(new Shipment(7L, "몬스테라"));
        jdbcTemplate.update("update shipment set status = 'DELIVERED', delivered_at = ? where id = ?",
                LocalDateTime.now().minusDays(1), outbound.getId());
        insertReturn(7L);
        insertReturn(7L);
        shipmentRepository.save(new Shipment(8L, "선인장"));
        insertReturn(8L);

        assertThat(shipmentQueryService.findBySellerOrderId(7L).shipmentId()).isEqualTo(outbound.getId());
        assertThat(shipmentQueryService.findBySellerOrderId(7L).status()).isEqualTo("DELIVERED");
        assertThat(shipmentQueryService.findBySellerOrderIds(List.of(7L, 8L))).hasSize(2)
                .allSatisfy((sellerOrderId, result) -> assertThat(jdbcTemplate.queryForObject(
                        "select direction from shipment where id = ?", String.class, result.shipmentId()))
                        .isEqualTo("OUTBOUND"));
        Long lockedId = new TransactionTemplate(transactionManager).execute(status -> shipmentRepository
                .findForUpdateBySellerOrderIdAndDirection(7L, ShipmentDirection.OUTBOUND).orElseThrow().getId());
        assertThat(lockedId).isEqualTo(outbound.getId());
    }

    @Test
    @DisplayName("가는 길 배송이 없고 반품 배송만 있으면 판매자 주문 배송 조회는 비어 있다")
    void returnOnlyIsNotFoundAsOutbound() {
        insertReturn(9L);

        assertThat(shipmentQueryService.findBySellerOrderId(9L)).isNull();
        assertThat(shipmentQueryService.findBySellerOrderIds(List.of(9L))).isEmpty();
    }

    // 반품 배송 생성은 #383이라 엔티티 대신 SQL로 넣는다
    private void insertReturn(Long sellerOrderId) {
        jdbcTemplate.update("insert into shipment (seller_order_id, status, direction, version) values (?, 'READY', 'RETURN', 0)",
                sellerOrderId);
    }
}
