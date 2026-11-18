package com.justcommit.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import io.micrometer.core.instrument.MeterRegistry;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
// 앱 전체를 띄우면 주문 만료 · 배송 예약 스케줄러도 돈다. 공유 DB에서 다른 테스트의 데이터를 건드리지 않게 끝나면 컨텍스트를 닫는다
@DirtiesContext
class MarketApplicationTests {

    // 테스트할 때마다 새 RSA 키를 만들어 공개키만 넣는다 (키를 파일에 남기지 않음)
    @DynamicPropertySource
    static void jwtPublicKey(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("jwt.public-key", MarketApplicationTests::newPublicKey);
        registry.add("internal-api.key", () -> "test-internal-key");
        // 판매자 배송지 이벤트 복호화 키도 실행마다 새로 만든다 (32바이트, 키를 파일에 남기지 않음)
        registry.add("messaging.address-event-cipher.keys.k1", MarketApplicationTests::newAddressEventKey);
        // 앱이 뜨는지만 보는 테스트라 로컬 Kafka의 이벤트를 실제로 읽지 않게 (읽으면 market 그룹의 읽은 위치가 기록됨)
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Test
    @DisplayName("빈 DB에서 Flyway V1 ~ V6가 적용되고 ddl-auto validate로 market 서비스가 뜬다")
    void contextLoads(@Autowired Flyway flyway) {
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion(), MigrationInfo::getState)
                .contains(tuple("1", MigrationState.SUCCESS), tuple("2", MigrationState.SUCCESS), tuple("3", MigrationState.SUCCESS),
                        tuple("4", MigrationState.SUCCESS), tuple("5", MigrationState.SUCCESS), tuple("6", MigrationState.SUCCESS));
    }

    @Test
    @DisplayName("확인 필요 주문 수 게이지(market_orders_review_required)가 지표 레지스트리에 등록된다")
    void registersReviewRequiredOrderGauge(@Autowired MeterRegistry meterRegistry) {
        assertThat(meterRegistry.find("market.orders.review.required").gauge()).isNotNull();
    }

    private static String newAddressEventKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static String newPublicKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
