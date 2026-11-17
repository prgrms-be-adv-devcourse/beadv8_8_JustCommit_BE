package com.justcommit.backend.ai.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.justcommit.backend.ai.product.domain.ProductSnapshot;
import com.justcommit.backend.ai.product.infrastructure.ProductSnapshotRepository;
import com.justcommit.backend.ai.product.infrastructure.messaging.ProductEventListener;
import com.justcommit.backend.common.messaging.EventEnvelope;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// 상품 이벤트 → 상품 스냅샷 (#293). Kafka 없이 리스너를 직접 불러, 순서 · 중복이 바뀌어도 productVersion으로 최신만 남는지 본다
@SpringBootTest
@Testcontainers
class ProductSnapshotIntegrationTest {

    private static final long SPECIES_ID = 14L;
    private static final long SELLER_ID = 7L;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", ProductSnapshotIntegrationTest::newPublicKey);
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        // Kafka 브로커 없이 리스너 메서드만 직접 부른다
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired ProductEventListener listener;
    @Autowired ProductSnapshotRepository snapshotRepository;
    @Autowired JdbcClient jdbcClient;

    @BeforeEach
    void clear() {
        jdbcClient.sql("DELETE FROM product_snapshot").update();
    }

    // 등록 → 검수 통과 → 주문 예약 → 예약 해제 → 다시 예약 → 판매 완료. 상품 버전은 바뀔 때마다 +1
    private static List<EventEnvelope> lifecycle(long productId) {
        return List.of(
                event("ProductRegistered", productId, "REVIEW_PENDING", false, 1),
                event("ProductStatusChanged", productId, "ACTIVE", false, 2),
                event("ProductStatusChanged", productId, "PAYMENT_PENDING", false, 3),
                event("ProductStatusChanged", productId, "ACTIVE", false, 4),
                event("ProductStatusChanged", productId, "PAYMENT_PENDING", false, 5),
                event("ProductStatusChanged", productId, "SOLD_OUT", false, 6));
    }

    @Test
    @DisplayName("순서대로 받으면 이벤트마다 스냅샷이 그 상태로 바뀐다")
    void inOrder() {
        for (EventEnvelope envelope : lifecycle(1L)) {
            listener.on(envelope);

            ProductSnapshot snapshot = snapshot(1L);
            assertThat(snapshot.status()).isEqualTo(envelope.payload().get("status").asString());
            assertThat(snapshot.productVersion()).isEqualTo(envelope.payload().get("productVersion").asLong());
        }
        assertThat(snapshot(1L)).isEqualTo(new ProductSnapshot(1L, SPECIES_ID, SELLER_ID, "SOLD_OUT", false, 6));
    }

    @Test
    @DisplayName("모든 이벤트를 두 번씩 받아도 마지막 상태가 같다")
    void duplicated() {
        for (EventEnvelope envelope : lifecycle(2L)) {
            listener.on(envelope);
            listener.on(envelope);
        }
        assertThat(snapshot(2L)).isEqualTo(new ProductSnapshot(2L, SPECIES_ID, SELLER_ID, "SOLD_OUT", false, 6));
    }

    @Test
    @DisplayName("거꾸로 받으면 처음 온 최신 버전만 남고 나머지는 버린다")
    void reversed() {
        List<EventEnvelope> events = new ArrayList<>(lifecycle(3L));
        Collections.reverse(events);

        events.forEach(listener::on);

        assertThat(snapshot(3L)).isEqualTo(new ProductSnapshot(3L, SPECIES_ID, SELLER_ID, "SOLD_OUT", false, 6));
    }

    @Test
    @DisplayName("섞어서 · 중복을 끼워 받아도 최신 버전 상태로 끝난다")
    void shuffledWithDuplicates() {
        Random random = new Random(293);
        for (long productId = 100; productId < 120; productId++) {
            List<EventEnvelope> events = new ArrayList<>(lifecycle(productId));
            events.addAll(lifecycle(productId).subList(0, random.nextInt(6) + 1));
            Collections.shuffle(events, random);

            events.forEach(listener::on);

            assertThat(snapshot(productId))
                    .isEqualTo(new ProductSnapshot(productId, SPECIES_ID, SELLER_ID, "SOLD_OUT", false, 6));
        }
    }

    @Test
    @DisplayName("같은 버전이 다른 값으로 다시 와도, 낮은 버전이 나중에 와도 스냅샷은 바뀌지 않는다")
    void sameOrLowerVersionIsIgnored() {
        listener.on(event("ProductStatusChanged", 4L, "PAYMENT_PENDING", false, 3));

        assertThat(snapshotRepository.upsertIfNewer(
                new ProductSnapshot(4L, SPECIES_ID, SELLER_ID, "ACTIVE", true, 3))).isFalse();
        listener.on(event("ProductStatusChanged", 4L, "ACTIVE", true, 3));
        listener.on(event("ProductStatusChanged", 4L, "ACTIVE", false, 2));

        assertThat(snapshot(4L)).isEqualTo(new ProductSnapshot(4L, SPECIES_ID, SELLER_ID, "PAYMENT_PENDING", false, 3));
    }

    @Test
    @DisplayName("숨김 · 품종 수정도 받은 값으로 덮어쓴다")
    void overwritesHiddenAndSpecies() {
        listener.on(event("ProductRegistered", 5L, "ACTIVE", false, 1));
        listener.on(envelope("ProductUpdated", 5L, payload(5L, 30L, "REVIEW_PENDING", false, 2)));
        listener.on(envelope("ProductStatusChanged", 5L, payload(5L, 30L, "ACTIVE", true, 3)));

        assertThat(snapshot(5L)).isEqualTo(new ProductSnapshot(5L, 30L, SELLER_ID, "ACTIVE", true, 3));
    }

    @Test
    @DisplayName("productId만 있는 payload(봉투 version 1)는 Jackson 예외라 재시도 없이 DLT로 가고 저장하지 않는다")
    void rejectsPayloadWithoutRequiredFields() {
        EventEnvelope v1 = new EventEnvelope(UUID.randomUUID().toString(), "ProductStatusChanged", 1,
                OffsetDateTime.now(), "6", json.valueToTree(Map.of("productId", 6)));

        assertThatThrownBy(() -> listener.on(v1)).isInstanceOf(JacksonException.class);
        assertThat(snapshotRepository.findById(6L)).isEmpty();
    }

    @Test
    @DisplayName("ProductApproved 같은 알림용 이벤트는 건너뛴다")
    void skipsOtherEvents() {
        listener.on(envelope("ProductApproved", 7L,
                Map.of("productId", 7, "sellerId", SELLER_ID, "reviewVersion", 1, "productTitle", "몬스테라")));

        assertThat(snapshotRepository.findById(7L)).isEmpty();
    }

    private ProductSnapshot snapshot(long productId) {
        return snapshotRepository.findById(productId).orElseThrow();
    }

    private static EventEnvelope event(String eventType, long productId, String status, boolean hidden,
            long productVersion) {
        return envelope(eventType, productId, payload(productId, SPECIES_ID, status, hidden, productVersion));
    }

    private static Map<String, Object> payload(long productId, long speciesId, String status, boolean hidden,
            long productVersion) {
        return Map.of("productId", productId, "speciesId", speciesId, "sellerId", SELLER_ID,
                "status", status, "hidden", hidden, "productVersion", productVersion);
    }

    private static EventEnvelope envelope(String eventType, long productId, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID().toString(), eventType, 2, OffsetDateTime.now(),
                String.valueOf(productId), JsonMapper.builder().build().valueToTree(payload));
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
